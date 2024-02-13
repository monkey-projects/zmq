(ns monkey.zmq.events
  "Functionality for setting up an event server and poster.  This uses ZeroMQ's
   push/pull socket types in the background."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.zmq.common :as mc]
            [zeromq.zmq :as z]))

(defn event-server [ctx addr handler & [opts]]
  (letfn [(receiver [s]
            (let [evt (z/receive s)]
              (handler (mc/parse-edn evt))))]
    (-> (mc/->Server ctx addr receiver :pull)
        (merge opts)
        (co/start))))

(defn event-poster
  "Creates an event poster that sends to the given address."
  [ctx addr]
  (let [s (doto (z/socket ctx :push)
            (z/connect addr))]
    (mc/->Client s #(z/send-str s (pr-str %2)))))
