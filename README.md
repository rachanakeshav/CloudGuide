# CloudGuide
AI-Powered Cloud Knowledge System

## Technologies Used

[![Akka](https://img.shields.io/badge/Akka-1384C4?style=for-the-badge&logo=akka&logoColor=white)](https://doc.akka.io/)  
[![Qdrant](https://img.shields.io/badge/Qdrant-FF4C8B?style=for-the-badge&logo=qdrant&logoColor=white)](https://qdrant.tech/documentation/)  
[![Java](https://img.shields.io/badge/Java-007396?style=for-the-badge&logo=java&logoColor=white)](https://docs.oracle.com/en/java/)  
[![Ollama](https://img.shields.io/badge/Ollama-000000?style=for-the-badge&logo=ollama&logoColor=white)](https://ollama.readthedocs.io/en/)  
![Streamlit](https://img.shields.io/badge/Streamlit-FF4B4B?style=for-the-badge&logo=streamlit&logoColor=white)

## Overview

CloudGuide is designed to assist small startups by answering a wide range of cloud-related questions, such as compute, storage, pricing, and cloud best practices. Using Akka for concurrency and LLMs (Large Language Models) for natural language understanding, this chatbot will provide accurate, context-rich answers and recommend cloud services based on user needs, while also offering pricing estimates.

## Problem Statement

Cloud service documentation and pricing information are often scattered, inconsistent, and difficult to navigate. Developers, students, and organizations frequently face challenges in retrieving accurate answers about cloud providers—whether it is comparing VM costs across regions, exploring service offerings, or understanding complex pricing models. Manually searching through provider portals and PDFs not only slows down productivity but also increases the risk of misinformed decisions, making cloud adoption more complex and time-consuming.

## Solution

CloudGuide provides a unified, intelligent knowledge retrieval system that consolidates documentation and live pricing into one accessible platform. Built using Akka actors for distributed scalability, the system integrates a Large Language Model (LLM) for natural language classification, a Qdrant vector database for semantic retrieval of cloud service documents, and live Pricing API calls for real-time cost information. The actor-based design allows queries to be routed efficiently—whether for documentation, pricing, or hybrid responses—while maintaining extensibility for future providers. By blending structured pricing data with unstructured documentation retrieval, CloudGuide offers a reliable, context-aware, and developer-friendly way to explore cloud services.

## Prerequisites

Before running CloudGuide, ensure you have the following installed:
	•	Java 17+ 
	•	Akka (Typed) 
	•	Qdrant (running locally via Docker or cloud) 
	•	Ollama (for running LLMs locally) 
	•	Streamlit

## How to Run the Application Locally

### Running the Backend (Akka + Java)

1. Clone the repository
2. Start Qdrant (via Docker):

    ```bash
    docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
3. Start Ollama and pull the desired LLM (example: llama3):

    ```bash
    ollama pull llama3
    ollama serve
4. Build and run the backend

    ```bash
    mvn -U clean compile
    mvn exec:java -DHOST=127.0.0.1 -DPORT=2551 -DROLE=api -DHTTP_PORT=8080
    mvn exec:java -Dexec.mainClass=com.cloudguide.Main -DROLE=llm -DPORT=2552
5. Test API Endpoints:

    a. RAG (Retrieval-Augmented Generation)

        curl -G "http://localhost:8080/api/ask" \ 
        --data-urlencode "text=ask: how to use the right pricing model in aws"

    b. LLM Only

        curl -G "http://localhost:8080/api/ask" \ 
        --data-urlencode "text=ask: briefly tell me about AWS cloudwatch"

    c. Pricing

        curl -G "http://localhost:8080/api/ask" \ 
        --data-urlencode "text=price azure storage archive grs in westus2"

### Running the Frontend

1. Move to frontend folder and start virtual env
2. ```pip install streamlit```
3. ```streamlit run app.py```

## Conclusion

CloudGuide demonstrates how modern cloud knowledge retrieval can be built using a distributed architecture powered by Akka Cluster, Qdrant vector database, and LLMs. By combining retrieval-augmented generation (RAG) with dynamic pricing queries, the system showcases both scalable information retrieval and real-world cloud service cost exploration.

Through the use of actor-based concurrency (tell, ask, forward) and the separation of nodes, the project highlights the flexibility of distributed systems design. The addition of a Streamlit frontend further bridges the gap between complex backend processing and an accessible user experience.

Overall, this application not only demonstrates document ingestion, RAG-based querying, and pricing integration, but also illustrates key software engineering concepts in resilience, modularity, and extensibility, laying a strong foundation for future enhancements such as sharding, load balancing, and multi-cloud support.

