# Collaborative Edition & Persistence protocol

This is a collection of design notes for collaborative edition feature
and persistence protocol.


## Persistence Operations

This is a page data structure:

```
{:version 2
 :options {}

 :rmap
 {:id1 :default
  :id2 :default
  :id3 :id1}

 :objects
 {:root
  {:type :root
   :shapes [:id1 :id2]}

  :id1
  {:type :canvas
   :shapes [:id3]}

  :id2 {:type :rect}
  :id3 {:type :circle}}}
```


This is a potential list of persistent ops:

```
{:type :mod-opts
 :operations [<op>, ...]

{:type :add-obj
 :id <uuid>
 :parent <uuid>
 :obj <shape-object>}

{:type :mod-obj
 :id   <uuid>
 :operations [<op>, ...]}

{:type :mov-obj
 :id <uuid>
 :frame-id <uuid>}

{:type :del-obj
 :id   <uuid>}
```

This is a potential list of operations:

```
{:type :set
 :attr <any>
 :val  <any>}

{:type :abs-order
 :id <uuid>
 :index <int>}
 
{:type :rel-order
 :id <uuid>
 :loc <one-of:up,down,top,bottom>}
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







