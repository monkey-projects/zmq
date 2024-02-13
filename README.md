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
:dependencies [[com.monkeyprojects/zmq "<VERSION>"]]
```

The clients and servers are grouped in namespaces.  See `monkey.zmq.req` for
request/reply, or `monkey.zmq.events` for events push/pull.  The server functions
return [components](https://github.com/stuartsierra/component) that should be started
or stopped.  The server will start a background thread that invokes the specified handler
on incoming data.  The client functions return an `AutoCloseable` object that is also
a function.

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

## Other Resources

- [CI/CD](https://monkeyci.com)

## License

Copyright (c) 2024 by Monkey Projects.

[GPL v3 License](LICENSE)