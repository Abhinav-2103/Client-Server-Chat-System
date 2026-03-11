
![Java](https://img.shields.io/badge/Language-Java-red?logo=java)
![Architecture](https://img.shields.io/badge/Architecture-Client--Server-blue)
![Status](https://img.shields.io/badge/Project-Completed-green)

A **Java-based client-server chat system** that allows multiple users to communicate through a centralized server.

The project demonstrates **socket programming, networking, and client-server architecture** in Java.

---

# 📌 Project Overview

This application uses **Java sockets** to enable real-time communication between clients connected to a server.

The system consists of:

- A **Chat Server** that manages connections
- Multiple **Chat Clients** that send and receive messages
- Storage of **chat history**
- User data management

---

# 🚀 Features

✅ Client-server communication using Java sockets  
✅ Multiple users can connect to the server  
✅ Real-time message exchange  
✅ Chat history storage  
✅ Basic user database  

---

# 🗂 Project Structure


Project 4/
│
├── ChatServer.java # Server application
├── ChatClient.java # Client application
│
├── users.db # User database
├── server_chat_history.txt # Server chat history
│
├── chat_history_Abhi.txt
├── chat_history_Abhisekh.txt
├── chat_history_Abhishek.txt
│
├── pom.xml # Maven configuration
└── images # Project resources


---

# 🛠 Technologies Used

- **Java**
- **Socket Programming**
- **Maven**
- **File Handling**
- **Client-Server Architecture**

---

# ⚙️ How It Works

1️⃣ Start the **Chat Server**

```bash
javac ChatServer.java
java ChatServer

2️⃣ Run the Chat Client

javac ChatClient.java
java ChatClient

3️⃣ Multiple clients can connect to the server and exchange messages.

📊 System Architecture
        Client 1
           |
           |
Client 2 —— Server —— Client 3
           |
           |
        Client 4

The server manages all connected clients and broadcasts messages.

📈 Future Improvements

Add GUI using Java Swing / JavaFX

Add encryption for secure messaging

Implement user authentication

Add private messaging

Store chat history in a database

👨‍💻 Author

Abhinav Thakur

GitHub:
https://github.com/Abhinav-2103
