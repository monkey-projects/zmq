(ns user
  (:require [clojure.tools.logging :as log]
            [zeromq.zmq :as z]
            [monkey.zmq
             [events :as e]
             [req :as r]]))

(def addr "inproc://test-server")

(defonce ctx (z/context))

(defn start-server []
  (r/server ctx addr (constantly "ok")))

(defn client []
  (r/client ctx addr))

(def events-addr "tcp://127.0.0.1:5555")

(defn event-server []
  (e/event-server ctx events-addr #(log/info "Got event:" %)))

(defn poster []
  (e/event-poster ctx events-addr))
