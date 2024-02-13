(ns monkey.zmq.req
  "Request/reply client and server"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.zmq.common :as mc]
            [zeromq.zmq :as z]))

(defn req
  "Creates a request for given service, with specified args"
  [svc args]
  {:id (random-uuid)
   :service svc
   :args args})

(defn rep
  "Creates a reply for given request, with specified value"
  [req v]
  {:request-id (:id req)
   :value v})

(defn server
  "Runs a server that binds to the given address.  Received requests 
   are passed to the handler."
  [ctx addr handler]
  (letfn [(receiver [s]
            (let [req (-> (z/receive s)
                          (mc/parse-edn))]
              (log/debug "Received:" req)
              (z/send-str s (pr-str (handler req)))))]
    (-> (mc/->Server ctx addr receiver :rep)
        (co/start))))

(def request-timeout 5000)

(defn- send-request [poller {:keys [socket] :as this} req]
  (log/debug "Sending request:" req)
  (z/send-str socket (pr-str req))
  (if (neg? (z/poll poller request-timeout))
    (throw (ex-info "Request error"
                    {:client this
                     :request req}))
    (if (z/check-poller poller 0 :pollin)
      (let [r (-> (z/receive socket)
                  (mc/parse-edn))]
        (log/debug "Got reply:" r)
        r)
      (throw (ex-info "Request timeout"
                      {:timeout request-timeout
                       :client this
                       :request req})))))

(defn client
  "Creates a synchronous client for the given destination.  Returns a client 
   object that sends a request and blocks until the reply has been received.
   Close it when you no longer need it."
  [ctx dest]
  ;; TODO Add retrying on disconnect
  (let [s (doto (z/socket ctx :req)
            (z/connect dest))
        poller (doto (z/poller ctx 1)
                 (z/register s :pollin))]
    (log/info "Connected to" dest)
    (mc/->Client s (partial send-request poller))))
