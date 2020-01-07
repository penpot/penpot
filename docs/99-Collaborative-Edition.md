# Collaborative Edition

This is a collection of design notes for collaborative edition feature.

## Persistence Ops

This is a page data structure:

```
{:shapes [<id>, ...]
 :canvas [<id>, ...]
 :shapes-by-id {<id> <object>, ...}}
```

This is a potential list of persistent ops:

```
;; Generic (Shapes & Canvas)
[:mod-shape <id> [:(mod|add|del) <attr> <val?>], ...] ;; Persistent

;; Example:
;; [:mod-shape 1 [:add :x 2] [:mod :y 3]]

;; Specific
[:add-shape <id> <object>]
[:add-canvas <id> <object>]

[:del-shape <id>]
[:del-canvas <id>]

[:mov-canvas <id> :after <id|null>] ;; null implies at first position
[:mov-shape <id> :after <id|null>]
```

## Ephemeral communication (Websocket protocol)


### `join` ###

Sent by clients for notify joining a concrete page-id inside a file.

```clojure
{:type :join
 :page-id <id>
 :version <number>
 }
```

Will cause:

- A posible `:page-changes`.
- Broadcast `:joined` message to all users of the file.

The `joined` message has this aspect:

```clojure
{:type :joined
 :page-id <id>
 :user-id <id>
 }
```

### `who` ###

Sent by clients for request the list of users in the channel.

```clojure
{:type :who}
```

Will cause:

- Reply to the client with the current users list:

```clojure
{:type :who
 :users #{<id>,...}}
```

This will be sent all the time user joins or leaves the channel for
maintain the frontend updated with the lates participants. This
message is also sent at the beggining of connection from server to
client.


### `pointer-update` ###

This is sent by client to server and then, broadcasted to the rest of
channel participants.

```clojure
{:type :pointer-update
 :page-id <id>
 :x <number>
 :y <number>
 }
```

The server broadcast message will look like:

```clojure
{:type :pointer-update
 :user-id <id>
 :page-id <id>
 :x <number>
 :y <number>
 }
```

### `:page-snapshot` ###

A message that server sends to client for notify page changes. It can be sent
on `join` and when a page change is commited to the database.

```clojure
{:type :page-snapshot
 :user-id <id>
 :page-id <id>
 :version <number>
 :operations [<op>, ...]
 }
```

This message is only sent to users that does not perform this change.







