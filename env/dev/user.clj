(ns user
  (:require [clojure.tools.logging :as log]
            [zeromq.zmq :as z]
            [manifold.stream :as ms]
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

(def broker-port 3300)

(defn broker-server
  ([ef]
   (e/broker-server (z/context) (str "tcp://0.0.0.0:" broker-port)
                    {:matches-filter? ef
                     :close-context? true}))
  ([]
   (broker-server (constantly true))))

(defn print-state [bs]
  (println (deref (ms/take! (:state-stream bs)) 100 :timeout)))

(defn broker-client
  ([ef]
   (let [c (e/broker-client (z/context) (str "tcp://127.0.0.1:" broker-port) println
                            {:close-context? true})]
     (e/register c ef)
     c))
  ([]
   (broker-client nil)))
