# Monkey ZMQ

A Clojure library that provides functionality for creating servers andclients
on top of [ZeroMQ](https://zeromq.org/).  This is used by [MonkeyCI](https://monkeyci.com)
to do communication between the various modules.

## Usage

Include it in your project `deps.edn`:

```clojure
{:deps {com.monkeyprojects/zmq {:mvn/version "<VERSION>"}}}
```
Or with Leiningen:
```clojure
(defproject ...
 :dependencies [[com.monkeyprojects/zmq "<VERSION>"]])
```

The clients and servers are grouped in namespaces.  See `monkey.zmq.req` for
request/reply, or `monkey.zmq.events` for events push/pull.  The server functions
return [components](https://github.com/stuartsierra/component) that should be started
or stopped.  The server will start a background thread that invokes the specified handler
on incoming data.  The client functions return an `AutoCloseable` object that is also
a function.

### Request/Reply

```clojure
(require '[monkey.zmq.req :as r])
(require '[zeromq.zmq :as z])

;; Create ZeroMQ context
(def ctx (z/context 1))

;; Create and start server
(require '[com.stuartsierra.component :as co])
(def server (-> (r/server ctx "inproc://test-server" (constantly "ok"))
                (co/start)))

;; Create client
(def client (r/client ctx "inproc://test-server"))

;; Send a request, wait for a reply
(def reply (client "Test request"))
;; => "ok"
```

The communication is always serialized to [EDN](https://github.com/edn-format/edn), so
you can also send structured information.

### Passive Events

The `monkey.zmq.events` namespace provides two kinds of event handling systems.  One is
a simple event server that only passively receives events, and dispatches them to a
`handler` function.  The other is a more complicated (and useful) event broker that
is both able to receive and send out events.

To create the passive event server and client:
```clojure
(require '[monkey.zmq.events :as e])
(require '[zeromq.zmq :as z])

(def ctx (z/context 1))
(def addr "inproc://passive-events")

;; Create and start server
(def server (e/event-server ctx addr println))
;; A client
(def client (e/event-poster ctx addr))
;; The client is a component that implements IFn and Autoclosable so you can
;; invoke it like this:
(client {:type :test-event :message "this is a test event"})

;; The server should now print out the event

;; Shut down
(.close client)
(.close server)
```

There is also a `close-all` utility function in `monkey.zmq.common` that closes all its args.

### Bidirectional Event Broker

A more useful event broker is also available in the events namespace.  It listens on an
endpoint (multiple endpoints coming later) and dispatches any events it receives to all
connected clients.  Clients must register with a filter in order to receive events.  How
this filter looks and how it's handled is completely up to you.  When starting the server
you need to specify a `matches-filter?` predicate that is passed an event and a filter.
It decides whether a client that is registered with a given filter is allowd to receive
the event.  By default, all events are allowed.

```clojure
(def addr "inproc://event-broker")

;; Start broker
(def broker (e/broker-server ctx [addr] {:matches-filter? (fn [evt evt-filter]
                                                            (= (:type evt) evt-filter))}))
;; Create a client and register it
(def client (e/broker-client ctx addr println))
(e/register client :test-event)

(client {:type :test-event :message "Should receive this"})
(client {:type :other-event :message "Should not receive this"})
```

This setup gives the user a lot of flexibility on how to filter events without sending
too much data to the clients and burdening them with their own filtering.  The only
condition is that the filter is serializable.  You could event use `eval` and allow
the client to send Clojure code as an event filter!  Whether that is a safe solution,
I'll leave that up to you to decide.

You can also have the broker listen on multiple addresses, just specify more than one in
the second argument to `broker-server`.

#### Other Options

Other options to pass to the broker client and server are:

|---|---|---|
|Option|Default|Description|
|---|---|---|
|`autostart?`|`true`|Will automatically [start](https://cljdoc.org/d/com.stuartsierra/component/1.1.0/api/com.stuartsierra.component#Lifecycle) the component.  If not, you'll have to start it yourself in order to enable the background thread.|
|`poll-timeout`|`500`|Number of millisecs to wait for incoming data.|
|`linger`|`0`|When closing the context, will block for this number of msecs to ensure all data is sent.  See also the [ZeroMQ documentation about lingering](http://zeromq.github.io/cljzmq/zeromq.zmq.html#var-set-linger).|
|`close-context?`|`false`|When true, the context passed in will also be closed when shutting down the component.|
|---|---|---|

## TODO

Things that still need to be implemented:

 - Implement a ping system to unregister any dead clients.
 - Make sure that events that match multiple filters for the same client only get sent once.
 - When sending information, first check if the socket can actually handle it.

## Other Resources

- [CI/CD](https://app.monkeyci.com/c/9eda9831-ea92-4a6c-95db-17bb9c9b2ab2/r/zmq)

## License

Copyright (c) 2024 by Monkey Projects.

[GPL v3 License](LICENSE)