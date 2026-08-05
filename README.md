````markdown
# 🍽️ Restaurant Reservation System

A full-stack restaurant reservation system developed as an academic software engineering project. The application combines a JavaFX desktop interface for restaurant staff with a web interface for customers, both connected through a local HTTP API and a centralized SQLite database.

The system simplifies reservation management by providing a visual table layout, automatic table assignment and real-time synchronization between desktop and web components.

---

## Features

### Desktop Application

- Interactive restaurant table map
- Create, edit and delete reservations
- Manual table selection
- Automatic table assignment
- Real-time occupancy monitoring
- Automatic refresh every 10 seconds
- Reservation management for restaurant staff

### Web Application

- Customer reservation form
- Interactive table selection
- Automatic table assignment
- Reservation confirmation
- Responsive interface
- Local communication with the backend through HTTP endpoints

### Reservation System

- Reservation validation
- Table availability verification
- Automatic seat allocation
- Customer information management
- Conflict prevention for overlapping reservations

---

## Technology Stack

| Technology | Purpose |
|------------|---------|
| Java 17 | Core application |
| JavaFX | Desktop graphical interface |
| SQLite | Local database |
| SQLite JDBC | Database connectivity |
| Maven | Dependency management |
| JDK HttpServer | Local REST API |
| HTML5 | Web interface |
| CSS3 | User interface styling |
| JavaScript | Client-side logic |
| Docker | Application deployment |

---

## Project Architecture

The application follows a modular architecture that separates presentation, business logic and data persistence.

```
                +------------------------+
                |     Web Interface      |
                |   HTML • CSS • JS      |
                +-----------+------------+
                            |
                      HTTP Requests
                            |
                +-----------v------------+
                |  ReservationHttpServer |
                +-----------+------------+
                            |
                    Business Logic
                            |
                +-----------v------------+
                |        DbUtil          |
                |       SQLite DB        |
                +-----------+------------+
                            |
                    Restaurant Database
```

The desktop application communicates directly with the same database, allowing both interfaces to remain synchronized.

---

## Database

The project uses SQLite as its local relational database.

### Tables

### `mese`

Stores information about restaurant tables.

Fields include:

- Table ID
- Name
- Capacity
- Area
- Position
- Window preference
- Locked status

### `rezervari`

Stores customer reservations.

Fields include:

- Reservation ID
- Customer name
- Number of guests
- Reservation date and time
- Assigned table
- Window preference
- Reservation status

---

## REST API

### GET `/mese`

Returns the available tables together with their current status for the selected date and time.

Example:

```http
GET /mese?datetime=2026-05-20T19:00
```

---

### POST `/rezervari`

Creates a new reservation.

The endpoint supports:

- Manual table selection
- Automatic table assignment

---

## Automatic Table Assignment

When the user selects the automatic option, the application follows the following process:

1. Search for an available table.
2. Verify the requested capacity.
3. Prioritize window tables if requested.
4. If no single table is available, search for compatible table combinations.
5. Save the reservation.
6. Refresh both interfaces.

This algorithm improves table utilization while reducing manual work for restaurant staff.

---

## Project Structure

```
RezervariRestaurant
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   └── resources/
│
├── package-resources/
├── dist/
├── dist-app/
├── docker-compose.yml
├── dockerfile
├── pom.xml
└── README.md
```

---

## Installation

### Requirements

- Java JDK 17 or newer
- Maven 3.9+
- SQLite
- Docker (optional)

---

### Clone Repository

```bash
git clone https://github.com/GeorgeB313/Sistem-Rezervare-Mese-Restaurant.git
```

---

### Build

```bash
mvn clean package
```

---

### Run

```bash
java -jar target/RezervariRestaurant.jar
```

or run the executable generated inside the `dist-app` folder.

---

## Docker

Start the application using Docker Compose.

```bash
docker compose up
```

---

## Software Engineering Concepts

This project demonstrates practical knowledge of:

- Object-Oriented Programming
- Desktop Application Development
- REST API Development
- Database Design
- SQL
- HTTP Communication
- Software Architecture
- JavaFX
- Multithreading
- MVC-inspired project organization
- Data Validation
- CRUD Operations
- Maven Build System
- Docker Deployment

---

## Future Improvements

Possible future extensions include:

- User authentication
- Role-based permissions
- Email reservation confirmations
- Reservation history
- Analytics dashboard
- Mobile application
- Online deployment
- Payment integration
- QR code reservations

---

## Author

**George-Florian Burlacu**

GitHub

https://github.com/GeorgeB313

---

Developed as a university Software Engineering project.
````
