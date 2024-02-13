(ns monkey.zmq.common
  (:require [byte-streams :as bs]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [zeromq.zmq :as z])
  (:import java.io.PushbackReader))

(defn parse-edn [v]
  (with-open [r (PushbackReader. (bs/to-reader v))]
    (edn/read r)))

(def poll-timeout 500)

(defn- run-sync-server
  "Runs a generic synchronous server.  It creates a socket of given type, binds it to the
   address and invokes the receiver when there is incoming data."
  [{:keys [context address receiver socket-type running?]}]
  ;; Sockets are not thread save so we must use them in the same thread
  ;; where we create them.
  (let [socket (doto (z/socket context socket-type)
                 (z/bind address))
        poller (doto (z/poller context 1)
                 (z/register socket :pollin))]
    (try
      (while (and @running?
                  (not (.. Thread currentThread isInterrupted)))
        (z/poll poller poll-timeout)
        (when (z/check-poller poller 0 :pollin)
          (receiver socket)))
      (catch Exception ex
        (log/error "Server error:" ex))
      (finally
        (reset! running? false)
        (z/set-linger socket 0)
        (z/close socket)))
    (log/info "Server terminated")))

(defrecord Server [context address receiver socket-type]
  co/Lifecycle
  (start [this]
    (log/info "Starting server at" address)
    (let [t (assoc this :running? (atom true))]
      (assoc t :thread (doto (Thread. #(run-sync-server t))
                         (.start)))))
  
  (stop [{:keys [thread running?] :as this}]
    (when thread
      (reset! running? false)
      (.interrupt thread)))

  java.lang.AutoCloseable
  (close [this]
    (co/stop this)))
