(ns monkey.zmq.events
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.zmq.common :as mc]
            [zeromq.zmq :as z]))

(defn event-server [ctx addr handler]
  (letfn [(receiver [s]
            (let [[_ evt] (z/receive-all s)]
              (handler (mc/parse-edn evt))))]
    (-> (mc/->Server ctx addr receiver :router)
        (co/start))))

(defrecord EventPoster [socket]
  clojure.lang.IFn
  (invoke [_ evt]
    (z/send-str socket (pr-str evt))
    (log/debug "Event posted"))

  java.lang.AutoCloseable
  (close [_]
    (z/close socket)))

(defn event-poster
  "Creates an event poster that sends to the given address."
  [ctx addr]
  (let [s (doto (z/socket ctx :dealer)
            (z/connect addr))]
    (->EventPoster s)))
