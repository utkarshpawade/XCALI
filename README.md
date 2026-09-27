# Collaborative Whiteboard

A real-time, multiplayer whiteboard in the spirit of Excalidraw. Sign up, create a board, share its name, and everyone in the room sees each other's shapes appear live. Every shape is saved, so a board looks the same when you come back to it.

The repository holds a **pnpm + Turborepo monorepo** with two Next.js frontends, and a **Spring Boot** backend, a standalone Maven project, that serves both the REST API and the WebSocket server from one process on one port.

---

## Contents

- [Features](#features)
- [Tech stack](#tech-stack)
- [Repository layout](#repository-layout)
- [Architecture](#architecture)
  - [System overview](#system-overview)
  - [Workspace dependency graph](#workspace-dependency-graph)
  - [Life of a shape](#life-of-a-shape)
- [Backend (`apps/backend`)](#backend-appsbackend)
  - [Packages](#packages)
  - [Class diagram: REST and domain](#class-diagram-rest-and-domain)
  - [Class diagram: real-time](#class-diagram-real-time)
  - [HTTP request pipeline](#http-request-pipeline)
  - [Authentication flow](#authentication-flow)
  - [Database schema](#database-schema)
  - [Configuration](#configuration)
- [Drawing app (`apps/excelidraw-frontend`)](#drawing-app-appsexcelidraw-frontend)
- [Chat demo (`apps/web`)](#chat-demo-appsweb)
- [Shared packages](#shared-packages)
- [API reference](#api-reference)
  - [REST endpoints](#rest-endpoints)
  - [WebSocket protocol](#websocket-protocol)
- [Getting started](#getting-started)
- [Scripts](#scripts)
- [Testing](#testing)
- [Deployment](#deployment)
- [Design notes and limitations](#design-notes-and-limitations)
- [Troubleshooting](#troubleshooting)

---

## Features

- **Infinite canvas** with pencil, rectangle, circle and text tools.
- **Pan and zoom**: hand tool, space + drag, middle-mouse drag or scroll to pan. Ctrl/Cmd + scroll, trackpad pinch or the toolbar to zoom from 10% to 800%, anchored at the cursor.
- **Real-time collaboration**: shapes are broadcast over WebSockets to everyone else in the room.
- **Persistent boards**: each shape is stored in PostgreSQL and replayed in drawing order when the board is opened.
- **Accounts**: email and password sign-up with JWT sessions. One account works in both frontends.
- **Shareable rooms**: boards are identified by a short name (slug) you can hand to anyone.
- **HiDPI-aware rendering**, batched through `requestAnimationFrame`.
- **Chat demo**: a second, minimal Next.js app that uses the same backend and protocol with plain-text messages.
- **Deployment recipes** for Docker Compose and Render + Vercel.

## Tech stack

| Layer | Technology |
| --- | --- |
| Frontend | Next.js 15 (App Router), React 19, TypeScript, Tailwind CSS 3, lucide-react, axios |
| Canvas | HTML5 Canvas 2D API with a custom engine ([`draw/Game.ts`](apps/excelidraw-frontend/draw/Game.ts)) |
| Shape validation | zod schemas in [`@repo/common`](packages/common/src/types.ts) |
| Backend | Spring Boot 4.1 on Java 21 (virtual threads): Spring MVC, Spring WebSocket, Spring Security, Spring Data JPA / Hibernate |
| Auth | HS256 JWTs (Nimbus, via `spring-security-oauth2-jose`), BCrypt password hashes (cost 10) |
| Database | PostgreSQL 16, schema managed by Flyway |
| Build | pnpm 9 workspaces and Turborepo 2 for the frontends, Maven wrapper for the backend |
| Tests | JUnit Jupiter, Spring Boot Test, embedded PostgreSQL (zonky), no Docker needed |
| Deployment | Docker, Docker Compose, Render Blueprint, Vercel |

## Repository layout

```text
.
├── apps/
│   ├── backend/                   Spring Boot REST + WebSocket server (Java 21, standalone Maven project)
│   │   ├── src/main/java/com/drawapp/backend/
│   │   │   ├── auth/              signup/signin, JWT issue/verify, security filter
│   │   │   ├── chat/              Chat entity (one row per shape), history endpoint
│   │   │   ├── room/              Room entity, create/list/lookup endpoints
│   │   │   ├── user/              User entity and repository
│   │   │   ├── realtime/          WebSocket handler, connection registry, frame parsing
│   │   │   ├── config/            security, CORS, WebSocket, .env and DATABASE_URL handling
│   │   │   └── common/            error model and JSON error handler
│   │   ├── src/main/resources/    application.yml, Flyway migrations
│   │   ├── src/test/              integration and unit tests
│   │   ├── mvnw, mvnw.cmd         Maven wrapper
│   │   └── Dockerfile
│   ├── excelidraw-frontend/       the whiteboard (Next.js 15)
│   │   ├── app/                   routes: /, /signin, /signup, /rooms, /canvas/[roomId]
│   │   ├── components/            AuthPage, RoomCanvas, Canvas, IconButton
│   │   ├── draw/                  Game (canvas engine) and the shape-history loader
│   │   ├── lib/                   axios client and token storage
│   │   ├── Dockerfile
│   │   └── vercel.json
│   └── web/                       minimal chat-room client (Next.js 15)
├── packages/
│   ├── common/                    zod schemas and TypeScript types for canvas shapes
│   ├── ui/                        shared React components (Button, Card, Code)
│   ├── eslint-config/             shared ESLint flat configs
│   └── typescript-config/         shared tsconfig bases
├── docker-compose.yml             local PostgreSQL + backend
├── render.yaml                    Render Blueprint (backend + PostgreSQL)
├── turbo.json                     Turborepo task graph (frontends and packages)
└── pnpm-workspace.yaml            pnpm workspace (the backend is not part of it)
```

---

## Architecture

### System overview

```mermaid
flowchart LR
    subgraph browser["Browser"]
        draw["Drawing app<br/>apps/excelidraw-frontend<br/>Next.js · :3000"]
        chat["Chat demo<br/>apps/web<br/>Next.js · :3002"]
    end

    subgraph backend["apps/backend · Spring Boot · :3001"]
        sec["Spring Security<br/>CORS + JWT filter"]
        rest["REST controllers<br/>/signup /signin /me<br/>/room /rooms /chats /health"]
        ws["CanvasWebSocketHandler<br/>/ws"]
        reg["ConnectionRegistry<br/>in-memory rooms"]
        svc["Services + JPA repositories"]
    end

    db[("PostgreSQL 16<br/>User · Room · Chat")]

    draw -->|"HTTPS + Bearer JWT"| sec
    chat -->|"HTTPS + Bearer JWT"| sec
    sec --> rest
    rest --> svc
    draw -->|"WSS /ws?token=JWT"| ws
    chat -->|"WSS /ws?token=JWT"| ws
    ws --> reg
    ws --> svc
    svc -->|"JDBC · Flyway"| db
```

- **One backend process** handles HTTP and WebSocket traffic on the same port. Flyway migrates the schema at startup, before the port opens, so there is no separate migration job.
- **The Next.js apps have no API routes and no server-side data fetching.** Every call to the backend is made from the browser, authenticated with the JWT kept in `localStorage`.
- **Real-time state is in memory.** `ConnectionRegistry` tracks which sockets are in which room, so the backend must run as a single instance (see [Design notes](#design-notes-and-limitations)).

### Workspace dependency graph

```mermaid
flowchart TD
    fe["apps/excelidraw-frontend"]
    web["apps/web"]
    common["@repo/common<br/>shape schemas + types"]
    ui["@repo/ui<br/>Button · Card · Code"]
    eslint["@repo/eslint-config"]
    tsconfig["@repo/typescript-config"]

    fe --> common
    fe --> ui
    web --> ui
    web --> eslint
    web --> tsconfig
    common --> tsconfig
    ui --> eslint
    ui --> tsconfig
```

Turborepo runs `build` and `dev` with `dependsOn: ["^build"]`, so `@repo/common` is compiled to `dist/` before any app that imports it. `@repo/ui` ships TypeScript source and needs no build step. The backend is not in the workspace: `apps/backend` has no `package.json`, so pnpm and Turborepo skip it, and it is built and run with its own Maven wrapper.

### Life of a shape

What happens when Alice draws a rectangle while Bob has the same board open:

```mermaid
sequenceDiagram
    autonumber
    actor Alice as Alice's browser
    participant WS as CanvasWebSocketHandler
    participant REG as ConnectionRegistry
    participant API as ChatController (REST)
    participant DB as PostgreSQL
    actor Bob as Bob's browser

    Alice->>WS: Upgrade GET /ws?token=JWT
    WS->>WS: JwtService.verify(token)
    alt token missing, invalid or expired
        WS-->>Alice: close 1008 Unauthorized
    else token valid
        WS->>REG: add(Connection)
    end
    Alice->>WS: join_room (roomId 7)
    WS->>REG: join(connection, "7")
    WS-->>Alice: joined_room (roomId 7)
    Alice->>API: GET /chats/7 with Bearer JWT
    API->>DB: select chats of room 7 ordered by id, limit 1000
    DB-->>API: rows
    API-->>Alice: messages
    Note over Alice: Game validates each row with ShapeSchema and replays it
    Alice->>Alice: user drags a rectangle, drawn locally at once
    Alice->>WS: chat (roomId 7, message = JSON of the shape)
    WS->>WS: sender must have joined room 7
    WS->>DB: INSERT INTO Chat
    WS->>REG: broadcast("7", except the sender)
    REG-->>Bob: chat (message, roomId, userId)
    Note over Bob: Game validates with ShapeSchema and redraws
```

The server treats the message as an opaque string: it checks the frame, stores the text verbatim and relays it. Shape validation happens in the browsers, through the shared `ShapeSchema`. The sender is never echoed its own shape, because it already drew it locally.

---

## Backend (`apps/backend`)

### Packages

| Package | Key types | Responsibility |
| --- | --- | --- |
| `auth` | `AuthController`, `AuthService`, `JwtService`, `JwtAuthenticationFilter`, `JsonAuthenticationEntryPoint` | Sign-up and sign-in, BCrypt hashing, issuing and verifying HS256 tokens, turning a bearer token into the request's principal (the user id). |
| `room` | `RoomController`, `RoomService`, `RoomRepository`, `Room` | Creating boards with unique slugs, listing the caller's boards, resolving a slug to an id. |
| `chat` | `ChatController`, `ChatService`, `ChatRepository`, `Chat` | Storing one row per shape and serving a room's history in drawing order. |
| `user` | `User`, `UserRepository`, `UserView` | The account entity and its public projection (never includes the password hash). |
| `realtime` | `CanvasWebSocketHandler`, `ConnectionRegistry`, `Connection`, `ClientMessage` | The WebSocket endpoint: authentication, room membership, persistence and broadcast, heartbeats, back-pressure. |
| `config` | `SecurityConfig`, `WebSocketConfig`, `AppProperties`, `EnvironmentSetup`, `DatabaseUrl` | Security filter chain, CORS, socket registration, `.env` loading and translating a `postgresql://` `DATABASE_URL` into JDBC settings. |
| `common` | `ApiException`, `ApiExceptionHandler`, `UniqueViolation` | A single JSON error format for every failure, and detecting unique-constraint violations (SQLSTATE `23505`) to answer with `409`. |

### Class diagram: REST and domain

```mermaid
classDiagram
    direction TB

    class AuthController {
        +signup(SignupRequest) SignupResponse
        +signin(SigninRequest) SigninResponse
        +me(String userId) CurrentUserResponse
    }
    class RoomController {
        +create(CreateRoomRequest, String userId) CreatedRoomResponse
        +list(String userId) RoomListResponse
        +get(String slug) RoomResponse
    }
    class ChatController {
        +history(String roomId) ChatHistoryResponse
    }
    class HealthController {
        +health() Health
    }

    class AuthService {
        -String dummyPasswordHash
        +signup(SignupRequest) Session
        +signin(SigninRequest) Session
        +currentUser(String userId) UserView
    }
    class JwtService {
        -JwtEncoder encoder
        -JwtDecoder decoder
        -Duration expiresIn
        +issue(String userId) String
        +verify(String token) Optional~String~
        +tokenFromAuthorizationHeader(String header)$ String
    }
    class RoomService {
        +create(String slug, String adminId) Room
        +listCreatedBy(String adminId) List~Room~
        +getBySlug(String slug) Room
        +parseId(String raw)$ Integer
    }
    class ChatService {
        +history(int roomId) List~Chat~
        +post(int roomId, String userId, String message) Chat
    }
    class PasswordEncoder {
        <<interface>>
        BCrypt, cost 10
    }

    class UserRepository {
        <<interface>>
        +findByEmail(String email) Optional~User~
    }
    class RoomRepository {
        <<interface>>
        +findBySlug(String slug) Optional~Room~
        +findByAdminIdOrderByCreatedAtDesc(String adminId, Limit limit) List~Room~
    }
    class ChatRepository {
        <<interface>>
        +findByRoomIdOrderByIdAsc(Integer roomId, Limit limit) List~Chat~
    }

    class User {
        <<Entity>>
        -String id
        -String email
        -String password
        -String name
        -String photo
    }
    class Room {
        <<Entity>>
        -Integer id
        -String slug
        -LocalDateTime createdAt
        -String adminId
    }
    class Chat {
        <<Entity>>
        -Integer id
        -Integer roomId
        -String message
        -String userId
    }

    AuthController --> AuthService
    RoomController --> RoomService
    ChatController --> ChatService
    ChatController ..> RoomService : parseId
    HealthController --> ConnectionRegistry : connection count

    AuthService --> UserRepository
    AuthService --> PasswordEncoder
    AuthService --> JwtService
    RoomService --> RoomRepository
    ChatService --> ChatRepository

    UserRepository ..> User
    RoomRepository ..> Room
    ChatRepository ..> Chat
    Room ..> User : adminId
    Chat ..> Room : roomId
    Chat ..> User : userId
```

Notes:

- Entities hold foreign keys as plain ids rather than JPA associations, which keeps queries explicit and the mapping identical to the table layout.
- Request bodies are Java records validated with Bean Validation. Their messages match the zod schemas in `@repo/common` word for word, because the frontends show them verbatim.
- `AuthService.signin` always runs a BCrypt comparison, against a throwaway hash when the email is unknown, so response time does not reveal whether an account exists.
- Lists are bounded: `/rooms` returns at most 50 boards and `/chats/{roomId}` at most 1,000 shapes.

### Class diagram: real-time

```mermaid
classDiagram
    direction TB

    class AbstractWebSocketHandler {
        <<Spring>>
    }
    class CanvasWebSocketHandler {
        <<Component>>
        +afterConnectionEstablished(WebSocketSession)
        #handleTextMessage(WebSocketSession, TextMessage)
        #handleBinaryMessage(WebSocketSession, BinaryMessage)
        #handlePongMessage(WebSocketSession, PongMessage)
        +handleTransportError(WebSocketSession, Throwable)
        +afterConnectionClosed(WebSocketSession, CloseStatus)
        +supportsPartialMessages() boolean
        -handle(Connection, String payload)
        -post(Connection, Post)
        -token(WebSocketSession)$ String
    }
    class ConnectionRegistry {
        <<Component>>
        -Map connections
        -Map rooms
        ~add(Connection)
        ~get(String sessionId) Connection
        ~remove(String sessionId)
        ~join(Connection, String roomId)
        ~leave(Connection, String roomId)
        ~broadcast(String roomId, Connection sender, TextMessage)
        +size() int
        ~heartbeat()
        ~closeAll()
    }
    class Connection {
        -WebSocketSession session
        -String userId
        -Set~String~ rooms
        -boolean alive
        -StringBuilder pending
        ~send(WebSocketMessage)
        ~close(CloseStatus)
        ~markAlive()
        ~ping() boolean
        ~append(String fragment, boolean last, int maxChars) String
    }
    class ClientMessage {
        <<sealed interface>>
        +roomId() String
        +from(JsonNode)$ ClientMessage
    }
    class JoinRoom {
        <<record>>
        String roomId
    }
    class LeaveRoom {
        <<record>>
        String roomId
    }
    class Post {
        <<record>>
        String roomId
        String message
    }

    AbstractWebSocketHandler <|-- CanvasWebSocketHandler
    CanvasWebSocketHandler --> ConnectionRegistry
    CanvasWebSocketHandler --> JwtService : verify token
    CanvasWebSocketHandler --> ChatService : persist shape
    CanvasWebSocketHandler ..> ClientMessage : parses frames
    ConnectionRegistry "1" o-- "*" Connection
    ClientMessage <|.. JoinRoom
    ClientMessage <|.. LeaveRoom
    ClientMessage <|.. Post
    WebSocketConfig --> CanvasWebSocketHandler : registers at /ws
```

How the pieces cooperate:

- **Authentication after the upgrade.** `/ws` is public in the security chain. The handler reads the token from `?token=` (browsers cannot set headers on a WebSocket) or from an `Authorization: Bearer` header, and closes an unauthenticated socket with **1008**. Browsers only expose the close code, and both frontends read 1008 as "sign in again".
- **Registry.** `connections` maps session id to `Connection`, and `rooms` maps room id to its members. `join` uses `ConcurrentHashMap.compute` so it cannot race `leave` dropping an emptied room, and it undoes itself if the socket closed while the join was in flight.
- **Thread-safe sends and back-pressure.** Each session is wrapped in a `ConcurrentWebSocketSessionDecorator` (10 s send time limit, 2 MB buffer). A client that falls too far behind is disconnected instead of slowing everyone else down.
- **Large frames.** `supportsPartialMessages()` is on, and `Connection.append` reassembles fragments up to 1,000,000 characters. An idle socket costs a few KB rather than a max-size buffer, and anything larger is closed with **1009**.
- **Heartbeat.** Every 30 s (`app.ws.heartbeat-interval`) the registry pings every socket. A socket that did not answer the previous ping is closed with **4500** and removed.
- **Graceful shutdown.** On `ContextClosedEvent` every socket is closed with **1001** before the web server stops. The server itself allows 10 s for in-flight requests.

### HTTP request pipeline

```mermaid
flowchart TD
    req["Incoming HTTP request"] --> cors["CORS<br/>origin checked against ALLOWED_ORIGINS"]
    cors --> jwt["JwtAuthenticationFilter<br/>reads the Authorization header"]
    jwt -->|"valid token"| ctx["SecurityContext<br/>principal = userId"]
    jwt -->|"invalid or expired"| flag["request flagged invalidToken<br/>(request continues)"]
    jwt -->|"no token"| authz
    ctx --> authz
    flag --> authz
    authz{"Public route?<br/>/health /signup /signin /ws /error"}
    authz -->|"yes"| ctrl["Controller"]
    authz -->|"no, authenticated"| ctrl
    authz -->|"no, anonymous"| e401["401<br/>Authentication required<br/>or Invalid or expired token"]
    ctrl --> valid{"Body passes @Valid?"}
    valid -->|"no"| e400["400 Incorrect inputs<br/>+ per-field errors"]
    valid -->|"yes"| svc["Service → Repository → PostgreSQL"]
    svc -->|"ApiException"| eapi["404 / 409 / 403<br/>with message"]
    svc -->|"success"| ok["200 / 201 JSON"]
```

A bad token does not fail the request in the filter. That keeps public routes such as `/signin` working when the browser still holds a stale token, while protected routes explain precisely why they refused. Sessions are stateless: CSRF, form login, HTTP Basic, logout and the request cache are all disabled.

### Authentication flow

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as AuthController
    participant S as AuthService
    participant R as UserRepository
    participant J as JwtService
    participant F as JwtAuthenticationFilter
    participant RC as RoomController

    B->>C: POST /signup (username, password, name)
    C->>C: Bean Validation
    C->>S: signup(request)
    S->>S: BCrypt hash, cost 10
    S->>R: saveAndFlush(User)
    alt email already registered
        R-->>S: unique violation (SQLSTATE 23505)
        S-->>B: 409 An account with this email already exists
    else created
        S->>J: issue(userId)
        J-->>S: HS256 JWT with userId, iat, exp
        C-->>B: 201 userId, user, token
    end
    Note over B: token saved in localStorage as excalidraw_token

    B->>F: GET /rooms with Authorization Bearer token
    F->>J: verify(token)
    J-->>F: userId
    F->>RC: principal = userId
    RC-->>B: 200 rooms created by this user
```

### Database schema

```mermaid
erDiagram
    User ||--o{ Room : "administers (adminId)"
    User ||--o{ Chat : "posts (userId)"
    Room ||--o{ Chat : "contains (roomId)"

    User {
        TEXT id PK "UUID"
        TEXT email UK "sign-in id, called username in the API"
        TEXT password "BCrypt hash"
        TEXT name
        TEXT photo "nullable, unused"
    }
    Room {
        SERIAL id PK
        TEXT slug UK "shareable board name"
        TIMESTAMP createdAt "UTC, defaults to now"
        TEXT adminId FK "ON DELETE RESTRICT"
    }
    Chat {
        SERIAL id PK "also the drawing order"
        INTEGER roomId FK "ON DELETE CASCADE"
        TEXT message "serialized shape, stored verbatim"
        TEXT userId FK "ON DELETE CASCADE"
    }
```

- The schema lives in [`V1__init_schema.sql`](apps/backend/src/main/resources/db/migration/V1__init_schema.sql). Flyway owns it; Hibernate runs with `ddl-auto: validate` and only checks the mapping.
- Tables and columns keep the quoted PascalCase and camelCase names (`"User"`, `"createdAt"`) of the Prisma schema they came from, via `PhysicalNamingStrategyStandardImpl` and globally quoted identifiers.
- `Chat("roomId", "id")` is indexed because every read is "all shapes of one room, in drawing order".
- `baseline-on-migrate` is on: a database that Prisma already created is recorded as version 1 and left untouched. Only an empty database runs `V1`.

### Configuration

Every setting comes from an environment variable. When the backend is started from `apps/backend` (`./mvnw spring-boot:run`), an `apps/backend/.env` file is loaded as well; see [`.env.example`](apps/backend/.env.example).

| Variable | Default | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | the docker-compose database (`localhost:5432/excalidraw`, `postgres`/`postgres`) | PostgreSQL connection string, either libpq/Prisma style (`postgresql://user:pass@host:5432/db?sslmode=require`) or `jdbc:postgresql://…`. |
| `JWT_SECRET` | an insecure development placeholder, with a warning | HMAC secret for signing tokens. At least 32 bytes. **Required** when the `prod` profile is active. Generate with `openssl rand -hex 48`. |
| `JWT_EXPIRES_IN` | `7d` | Token lifetime: `7d`, `12h`, `30m`, … |
| `PORT` | `3001` | HTTP and WebSocket port. |
| `ALLOWED_ORIGINS` | `*` | Comma-separated browser origins allowed by CORS **and** on the WebSocket handshake. Use `*` only in development. |
| `SPRING_PROFILES_ACTIVE` | none (the Docker image sets `prod`) | With `prod`, startup fails if `JWT_SECRET` is missing, too short or still the development placeholder. |
| `APP_WS_HEARTBEATINTERVAL` | `30s` | Socket ping interval (`app.ws.heartbeat-interval`). |

**Precedence**, highest first:

1. Real environment variables, including Spring's own names such as `SPRING_DATASOURCE_URL`.
2. `apps/backend/.env`, which never overrides a real variable, like dotenv.
3. Datasource settings derived from `DATABASE_URL`.
4. Defaults in [`application.yml`](apps/backend/src/main/resources/application.yml).

[`DatabaseUrl`](apps/backend/src/main/java/com/drawapp/backend/config/DatabaseUrl.java) translates the URL format that Render, Neon, Supabase and RDS hand out. It splits out and percent-decodes the credentials (tolerating an unencoded `@` in the password), renames libpq parameters that pgjdbc spells differently (`schema` → `currentSchema`, `connect_timeout` → `connectTimeout`, …), maps Prisma's `pgbouncer=true` to `prepareThreshold=0` and drops Prisma-only options such as `connection_limit`.

---

## Drawing app (`apps/excelidraw-frontend`)

### Routes

| Route | Implementation | Access | Purpose |
| --- | --- | --- | --- |
| `/` | [`app/page.tsx`](apps/excelidraw-frontend/app/page.tsx) | public | Landing page. |
| `/signin`, `/signup` | [`AuthPage`](apps/excelidraw-frontend/components/AuthPage.tsx) | public | Stores the returned token, then goes to `/rooms`. |
| `/rooms` | [`app/rooms/page.tsx`](apps/excelidraw-frontend/app/rooms/page.tsx) | client-side guard, redirects to `/signin` | Lists the boards you created, creates a new board, joins one by name. |
| `/canvas/[roomId]` | [`RoomCanvas`](apps/excelidraw-frontend/components/RoomCanvas.tsx) → [`Canvas`](apps/excelidraw-frontend/components/Canvas.tsx) → [`Game`](apps/excelidraw-frontend/draw/Game.ts) | needs a token | The board itself. |

### Class diagram

```mermaid
classDiagram
    direction TB

    class RoomCanvas {
        <<React component>>
        +roomId string
        -socket WebSocket
        -status Status
    }
    class Canvas {
        <<React component>>
        +roomId string
        +socket WebSocket
        -selectedTool Tool
        -zoom number
        -textEdit TextEditRequest
        -commitTextEdit()
    }
    class IconButton {
        <<React component>>
        +icon ReactNode
        +label string
        +activated boolean
        +onClick()
    }
    class Game {
        -existingShapes Array~Shape~
        -selectedTool Tool
        -camera Camera
        -pencilPoints Array~Point~
        -renderHandle number
        +setTool(tool)
        +onCamera(listener)
        +onTextEdit(listener)
        +commitText(world, content)
        +zoomBy(factor)
        +resetView()
        +destroy()
        -init() Promise
        -initHandlers()
        -applyZoom(factor, anchor)
        -buildShape() Shape
        -publish(shape)
        -scheduleRender()
        -render()
        -drawShape(shape)
    }
    class Camera {
        <<interface>>
        +offsetX number
        +offsetY number
        +scale number
    }
    class TextEditRequest {
        <<interface>>
        +screenX number
        +screenY number
        +world Point
    }
    class ShapeHistory {
        <<module draw/http.ts>>
        +getExistingShapes(roomId) Promise
    }
    class ApiClient {
        <<module lib/api.ts>>
        +signup(input)
        +signin(input)
        +listRooms()
        +createRoom(name)
        +getRoomBySlug(slug)
        +getErrorMessage(error) string
    }
    class TokenStore {
        <<module lib/auth.ts>>
        +getToken() string
        +setToken(token)
        +clearToken()
        +isAuthenticated() boolean
    }

    RoomCanvas *-- Canvas : renders once the socket is open
    Canvas *-- Game : creates in useEffect
    Canvas o-- IconButton : toolbars
    Game --> Camera
    Game ..> TextEditRequest : emits
    Game ..> ShapeHistory : loads history
    ShapeHistory ..> ApiClient
    ApiClient ..> TokenStore : adds Bearer header
    RoomCanvas ..> TokenStore : reads JWT
```

React owns the WebSocket and the UI chrome (toolbar, zoom readout, text overlay). `Game` is a plain TypeScript class that owns the `<canvas>`: input, camera, shape list and rendering. It never closes the socket, because the socket belongs to `RoomCanvas`.

### Canvas engine

- **Coordinates.** Shapes are stored in *world* coordinates. A pointer event maps to world space as `world = (screen − offset) / scale`. Rendering applies `setTransform(scale·dpr, 0, 0, scale·dpr, offsetX·dpr, offsetY·dpr)`, and the line width is `2 / scale` so strokes keep the same on-screen thickness at any zoom.
- **Rendering.** Every change calls `scheduleRender()`, which coalesces into at most one full redraw per animation frame. A `ResizeObserver` keeps the backing store at CSS size × `devicePixelRatio`, so lines stay sharp on HiDPI screens.
- **Drawing.** Pointer capture keeps a drag alive outside the canvas. Rectangles are normalised so dragging up or left still gives a positive size. Shapes smaller than 1 unit are discarded. Pencil points closer than 1 unit to the previous one are skipped, and a stroke is capped at 5,000 points to keep frames small.
- **Sync.** A finished shape is added locally first (optimistic), then sent as a `chat` frame if the socket is open. Incoming frames and history rows are parsed with `ShapeSchema.safeParse`, and anything invalid is ignored instead of breaking the canvas.
- **Text.** Clicking with the text tool opens a transparent `<textarea>` at the click point. Enter or blur commits, Esc cancels.

| Action | Input |
| --- | --- |
| Pan | Hand tool, hold <kbd>Space</kbd> and drag, middle-mouse drag, or scroll / two-finger swipe |
| Zoom | <kbd>Ctrl</kbd>/<kbd>Cmd</kbd> + scroll, trackpad pinch, or the toolbar (±20% per click), between 10% and 800% |
| Reset view | Toolbar button (origin, 100%) |
| Text | Click with the text tool, then <kbd>Enter</kbd> to place or <kbd>Esc</kbd> to cancel |

### Connection states

```mermaid
stateDiagram-v2
    [*] --> connecting : token found
    [*] --> unauthorized : no token, redirect to /signin
    connecting --> open : onopen, send join_room
    connecting --> failed : onerror
    open --> unauthorized : closed with 1008
    open --> failed : closed with any other code
    failed --> connecting : Retry reloads the page
    unauthorized --> [*] : Sign in again
```

---

## Chat demo (`apps/web`)

A deliberately small client, running on port 3002, that exercises the same backend with plain-text messages:

- The home page signs you in or up; the account is the same one the drawing app uses.
- `/room/[slug]` resolves the slug through `GET /room/{slug}`, loads history through `GET /chats/{id}` and joins the room through the [`useSocket`](apps/web/hooks/useSocket.ts) hook.
- Messages are sent as `chat` frames whose `message` is plain text.

Chat messages and shapes share the `Chat` table. The drawing app skips rows that are not valid shapes, and the chat app shows every row as raw text, so shapes appear there as JSON.

---

## Shared packages

### `@repo/common`

Imported as `@repo/common/types`. Compiled with `tsc` to `dist/`, which Turborepo does before the apps start.

| Export | Used for |
| --- | --- |
| `PointSchema`, `ShapeSchema`, `Point`, `Shape` | The shape model drawn, sent and replayed by the canvas. |

```mermaid
classDiagram
    direction LR
    class Shape {
        <<union on type>>
    }
    class Rect {
        type = rect
        number x
        number y
        number width
        number height
    }
    class Circle {
        type = circle
        number centerX
        number centerY
        number radius
    }
    class Pencil {
        type = pencil
    }
    class Text {
        type = text
        number x
        number y
        string content
        number fontSize
    }
    class Point {
        number x
        number y
    }
    Shape <|-- Rect
    Shape <|-- Circle
    Shape <|-- Pencil
    Shape <|-- Text
    Pencil "1" *-- "1..5000" Point : points
```

All numbers must be finite. `radius` must be non-negative, `fontSize` positive, and text `content` 1–2,000 characters.

### `@repo/ui`

Shared React components, exported as TypeScript source (no build step). The drawing app's Tailwind config scans `packages/ui/src`, so the components share its theme.

- `Button`: `variant` of `primary`, `secondary` or `outline`; `size` of `lg` or `sm`.
- `Card`: optional `title`; with `href` it becomes a link, and external links open in a new tab.
- `Code`: a styled `<code>`.

A new component can be scaffolded with `pnpm --filter @repo/ui generate:component`.

### `@repo/eslint-config` and `@repo/typescript-config`

Shared ESLint flat configs (`base`, `next-js`, `react-internal`) and tsconfig bases (`base.json`, `nextjs.json`, `react-library.json`).

---

## API reference

Base URL in development: `http://localhost:3001`. Every response body is JSON.

### REST endpoints

| Method | Path | Auth | Request body | Success | Errors |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/health` | no | | `200 {status, uptime, connections}` | |
| `POST` | `/signup` | no | `{username, password, name}` | `201 {userId, user, token}` | `400` validation, `409` email taken |
| `POST` | `/signin` | no | `{username, password}` | `200 {token, user}` | `400` validation, `403` wrong email or password |
| `GET` | `/me` | yes | | `200 {user}` | `401`, `404` |
| `POST` | `/room` | yes | `{name}` | `201 {roomId, slug}` | `400` validation, `401`, `409` name taken |
| `GET` | `/rooms` | yes | | `200 {rooms: [{id, slug, createdAt}]}`, newest first, at most 50, only boards you created | `401` |
| `GET` | `/room/{slug}` | yes | | `200 {room: {id, slug, createdAt}}` | `401`, `404` |
| `GET` | `/chats/{roomId}` | yes | | `200 {messages: [{id, roomId, message, userId}]}`, oldest first, at most 1,000 | `400` invalid id, `401` |

Validation rules:

- `username`: an email address. It is stored in the `email` column.
- `password`: at least 6 characters on sign-up.
- `name`: 1–50 characters.
- Room `name`: 3–20 characters, letters, digits, `-` and `_` only.

Authenticated requests send `Authorization: Bearer <token>`. A bare token without the `Bearer ` prefix is accepted too.

**Error format**: every error is `{"message": "..."}`. Validation failures add an `errors` map with the first failing check of each field, in field order:

```json
{
  "message": "Incorrect inputs",
  "errors": {
    "username": "A valid email address is required",
    "password": "Password must be at least 6 characters"
  }
}
```

A `401` says why it was refused: `"Authentication required"` when no token was sent, `"Invalid or expired token"` when one was.

**Example session**:

```bash
# Sign up; the response carries a token
curl -s -X POST http://localhost:3001/signup \
  -H 'Content-Type: application/json' \
  -d '{"username":"ada@example.com","password":"secret1","name":"Ada"}'

TOKEN="<token from the response>"

# Create a board and read its history
curl -s -X POST http://localhost:3001/room \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"design-review"}'

curl -s http://localhost:3001/chats/1 -H "Authorization: Bearer $TOKEN"
```

### WebSocket protocol

Connect to `ws://localhost:3001/ws?token=<JWT>` (or send `Authorization: Bearer <JWT>` on the handshake from non-browser clients). The origin must be in `ALLOWED_ORIGINS`. All frames are JSON text.

**Client → server**

| `type` | Fields | Effect |
| --- | --- | --- |
| `join_room` | `roomId` (string or number) | Adds the socket to the room and replies `joined_room`. |
| `leave_room` | `roomId` | Removes the socket from the room. No reply. |
| `chat` | `roomId`, `message` (string, at most 200,000 characters) | The sender must have joined the room. The message is saved, then relayed to every *other* member. |

Numeric room ids are normalised the way JavaScript's `String()` prints them, so `7` and `"7"` are the same room.

**Server → client**

| `type` | Fields | When |
| --- | --- | --- |
| `joined_room` | `roomId` | Acknowledges `join_room`. |
| `chat` | `message`, `roomId`, `userId` | Another member posted to a room you joined. |
| `error` | `message` | `Malformed JSON`, `Unsupported message`, `Join the room before sending`, `Invalid room id`, or `Could not save your drawing`. |

A shape is sent as a `chat` frame whose `message` is the JSON string of `{"shape": …}`:

```json
{
  "type": "chat",
  "roomId": "7",
  "message": "{\"shape\":{\"type\":\"rect\",\"x\":10,\"y\":20,\"width\":120,\"height\":80}}"
}
```

**Close codes**

| Code | Reason |
| --- | --- |
| `1008` | Missing, invalid or expired token. |
| `1009` | A frame larger than 1,000,000 characters. |
| `1001` | The server is shutting down. |
| `4500` | The client did not answer a heartbeat ping. |

To try it by hand: `npx wscat -c "ws://localhost:3001/ws?token=$TOKEN"`, then type `{"type":"join_room","roomId":1}`.

---

## Getting started

### Prerequisites

- **Node.js 20+** (the Docker images use 22)
- **pnpm 9**: `corepack enable` activates the version pinned in `package.json`
- **JDK 21**, to run the backend outside Docker
- **Docker**, for PostgreSQL (and optionally the backend)

### 1. Install dependencies

```bash
corepack enable
pnpm install
```

### 2. Start PostgreSQL

`docker-compose.yml` marks `JWT_SECRET` as required, so set it first, either in your shell or in a `.env` file at the repository root:

```bash
export JWT_SECRET=$(openssl rand -hex 48)
docker compose up -d postgres
```

> On Windows PowerShell use `$env:JWT_SECRET = "<a random string of 32+ characters>"`. `openssl` is also available in Git Bash.

### 3. Configure the apps

```bash
cp apps/backend/.env.example apps/backend/.env
cp apps/excelidraw-frontend/.env.example apps/excelidraw-frontend/.env.local
cp apps/web/.env.example apps/web/.env.local
```

The defaults point at the compose database and at `localhost:3001`. `JWT_SECRET` may stay empty in development; the backend falls back to a placeholder and logs a warning.

### 4. Run everything

The backend and the frontends start separately. In one terminal, start the backend:

```bash
cd apps/backend
./mvnw spring-boot:run        # mvnw.cmd spring-boot:run on Windows
```

In a second terminal, from the repository root, start the frontends. Turborepo builds `@repo/common` first:

```bash
pnpm dev
```

| App | URL |
| --- | --- |
| Drawing app | <http://localhost:3000> |
| Backend | <http://localhost:3001/health>, socket at `ws://localhost:3001/ws` |
| Chat demo | <http://localhost:3002> |

To start a single app, filter it: `pnpm dev --filter excelidraw-frontend`.

**Backend in Docker instead of a local JDK**:

```bash
export JWT_SECRET=$(openssl rand -hex 48)
docker compose up -d --build              # PostgreSQL + backend on :3001
pnpm dev --filter excelidraw-frontend
```

### 5. Try it

1. Open <http://localhost:3000>, sign up and create a board, for example `design-review`.
2. Open a private window (it has its own `localStorage`), sign up as a second user and join `design-review`.
3. Draw in one window and watch the shapes appear in the other.

---

## Scripts

Frontends and shared packages, run from the repository root:

| Command | What it does |
| --- | --- |
| `pnpm dev` | Starts both Next.js apps in watch mode. |
| `pnpm build` | Builds the Next.js apps and `@repo/common`. |
| `pnpm lint` | Lints every package. |
| `pnpm check-types` | Runs `tsc --noEmit` across the TypeScript packages. |
| `pnpm format` | Formats `ts`, `tsx` and `md` files with Prettier. |
| `pnpm clean` | Removes build output. |

Backend, run from `apps/backend` (`mvnw.cmd` instead of `./mvnw` on Windows):

| Command | What it does |
| --- | --- |
| `./mvnw spring-boot:run` | Starts the backend on port 3001. |
| `./mvnw test` | Runs the backend test suite against an embedded PostgreSQL; no Docker needed. |
| `./mvnw package` | Builds `target/backend-1.0.0.jar`, which runs with `java -jar target/backend-1.0.0.jar`. |
| `./mvnw clean` | Removes `target/`. |

---

## Deployment

The backend runs on **Render**, the whiteboard on **Vercel**, and the data in an external PostgreSQL database (Neon, Supabase or any other host). Each app needs the other's URL: the frontend inlines the backend URL at build time, and the backend only accepts requests and sockets from the frontend's origin. So deploy Render first, then Vercel, then point Render back at Vercel.

### 1. Backend on Render

[`render.yaml`](render.yaml) is a Blueprint that creates a Render project named `xcali` containing the Spring Boot service (`xcali-backend`), on the free plan in Singapore. Pick the region closest to the database, since every shape is a database write.

1. Render Dashboard → **New** → **Blueprint**, pick this repository and apply it.
2. When asked for `DATABASE_URL`, paste the database's connection string, for example `postgresql://user:pass@host/db?sslmode=require`.
3. When asked for `ALLOWED_ORIGINS`, enter the URL the frontend will have, for example `https://xcali.vercel.app`. It can be corrected in step 3.

`JWT_SECRET` is generated. Flyway creates the schema on first start. Once the deploy is live, `https://<service>.onrender.com/health` answers.

### 2. Whiteboard on Vercel

1. Vercel → **Add New** → **Project**, import this repository.
2. Set **Root Directory** to `apps/excelidraw-frontend`. [`vercel.json`](apps/excelidraw-frontend/vercel.json) installs from the workspace root and builds through Turborepo, so the other settings stay at their defaults.
3. Add these environment variables, then deploy:

| Variable | Value |
| --- | --- |
| `NEXT_PUBLIC_HTTP_BACKEND` | `https://<service>.onrender.com` |
| `NEXT_PUBLIC_WS_URL` | `wss://<service>.onrender.com/ws` |

Both are inlined by `next build`, so changing them later needs a redeploy.

### 3. Connect the two

On Render, set `ALLOWED_ORIGINS` on `xcali-backend` to the Vercel production URL, with no trailing slash. Render restarts the service with the new value. Preview deployments get their own URLs; add them to the comma-separated list if they need to reach the API.

### Free-tier limits

- The free Render service **spins down after 15 minutes** without traffic, and the next request waits about a minute while it starts again.

---

## Design notes and limitations

- **Single backend instance.** Socket membership lives in process memory with no shared pub/sub. Two instances would split a room's members between them, so every deployment config pins the backend to one replica. Scaling out would need a broker such as Redis pub/sub.
- **Boards are append-only.** Shapes cannot be selected, moved, erased or undone; each one is an immutable row.
- **History is capped at the first 1,000 shapes.** `/chats/{roomId}` returns the oldest 1,000 rows, so on a busier board, shapes drawn after that are relayed live but not replayed on reload.
- **Rooms are unlisted, not private.** Any signed-in user who knows a board's name or id can join it, read its history and draw on it. `/rooms` only lists the boards you created.
- **The server does not validate shapes.** It bounds the size of messages but stores them verbatim; the browsers validate with `ShapeSchema` and skip anything invalid.
- **Tokens live in `localStorage`** and travel in the WebSocket query string, because browsers cannot set headers on a WebSocket. Query strings can end up in proxy logs, which is part of that trade-off.

---

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| CORS errors in the browser console, or the board shows "Lost the connection to the drawing server" right away | `ALLOWED_ORIGINS` does not contain the frontend's exact origin (scheme, host and port, no trailing slash). The same list gates the WebSocket handshake. |
| "Your session has expired" on the board | The socket was closed with 1008: the token is missing, expired, or was signed with a different `JWT_SECRET`. Sign in again. |
| "Cannot reach the server. Is the backend running?" | The browser could not reach `NEXT_PUBLIC_HTTP_BACKEND`. Check that the backend answers on `/health`. |
| Backend exits with `JWT_SECRET is not set. Refusing to start in production…` | The `prod` profile (the Docker image default) requires a real secret. Set `JWT_SECRET`. |
| Backend exits with `JWT_SECRET must be at least 32 bytes for HS256` | Use a longer secret, for example `openssl rand -hex 48`. |
| `docker compose` fails with `set JWT_SECRET in your shell or a root .env file` | Export `JWT_SECRET` or put it in a `.env` file at the repository root. |
| A deployed frontend still calls `localhost:3001` | `NEXT_PUBLIC_*` were not set when `next build` ran. Set them and rebuild. |
| `next build` fails with `EPERM` on Windows | `BUILD_STANDALONE=1` makes Next recreate pnpm's symlinks, which needs Windows Developer Mode. Build without it locally; only the Docker image needs standalone output. |
