(ns monkey.zmq.core
  (:require [zeromq.zmq :as z]))

#_(def addr "inproc://test")
(def addr "tcp://127.0.0.1:5555")

(defn interrupted? []
  (.. Thread currentThread isInterrupted))

(defn inproc-server [ctx]
  (with-open [server (doto (z/socket ctx :pull)
                       (z/set-receive-timeout 1000)
                       (z/bind addr))]
    (loop [r (z/receive-str server)]
      (when r
        (println "Received:" r))
      (when-not (interrupted?)
        (recur (z/receive-str server))))))

(defn inproc-client [ctx]
  (with-open [client (doto (z/socket ctx :push)
                       (z/connect addr))]
    (println "Sending hi")
    (z/send-str client "Hi!")
    (z/send-str client "Hi, again!")
    (println "Done sending.")))

(defn ->thread [f]
  (doto (Thread. f)
    (.start)))

(defn run-test []
  (let [ctx (z/context 1)
        ctx-f (fn [f]
                #(f ctx))
        [s c] (->> [inproc-server inproc-client]
                   (map ctx-f)
                   (map ->thread)
                   (doall))]
    (try
      (.join c)
      (println "Sender terminated")
      (Thread/sleep 1000)
      (.interrupt s)
      (println "Waiting for receiver to stop...")
      (.join s)
      (println "Done.")
      (finally
        (.close ctx)))))

