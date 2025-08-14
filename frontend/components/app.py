# ui_app.py
import json
import requests
import streamlit as st

# ---- Config ----
API_BASE = "http://localhost:8080"

st.set_page_config(page_title="CloudGuide", page_icon=":robot_face:", layout="centered")

# ---- State ----
if "messages" not in st.session_state:
    st.session_state.messages = []   

st.title("CloudGuide")

with st.sidebar:
    st.markdown("### Settings")
    api_base = st.text_input("API base URL", value=API_BASE)
    use_rag = st.toggle("Use docs (RAG)", value=False,
                        help="When ON, your message will be prefixed with `ask:` to route through retrieval.")

st.markdown(
    "Welcome to **CloudGuide**! Ask me about AWS/Azure/GCP pricing, architecture, "
    "and cloud ops. Toggle **Use docs (RAG)** to get answers from your ingested PDFs."
)

# ---- Chat render ----
for m in st.session_state.messages:
    with st.chat_message("user" if m["role"] == "user" else "assistant"):
        if m["role"] == "assistant":
            st.markdown(m["text"])
            if "source" in m and m["source"]:
                st.caption(f"Source: {m['source']}")
        else:
            st.markdown(m["text"])

# ---- Input ----
prompt = st.chat_input("Type your message…")

if prompt:
    # 1) append user message
    st.session_state.messages.append({"role": "user", "text": prompt})
    with st.chat_message("user"):
        st.markdown(prompt)

    # 2) prepare query (RAG toggle => prefix ask:)
    query = prompt.strip()
    if use_rag:
        if not query.lower().startswith("ask:"):
            query = "ask: " + query

    # 3) call backend 
    try:
        r = requests.get(f"{api_base}/api/ask", params={"text": query}, timeout=30)
        r.raise_for_status()

        # Always start with raw text
        text_final = r.text
        source = r.headers.get("x-source", "")

        # If backend sent JSON, extract "text" (and optional "source")
        if text_final.startswith("{") and "\"text\"" in text_final:
            try:
                data = r.json()
                text_final = str(data.get("text", "")).strip() or text_final
                source = str(data.get("source", "")).strip()
            except Exception:
                pass  # fall back to raw text

        # Append assistant message (string only — no tuples/dicts)
        st.session_state.messages.append({"role": "assistant", "text": text_final, "source": source})

        with st.chat_message("assistant"):
            st.markdown(text_final)
            if source:
                st.caption(f"Source: {source}")

    except requests.RequestException as e:
        err = f"Error talking to API: {e}"
        st.session_state.messages.append({"role": "assistant", "text": err, "source": ""})
        with st.chat_message("assistant"):
            st.error(err)