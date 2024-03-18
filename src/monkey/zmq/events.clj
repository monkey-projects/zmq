(ns monkey.zmq.events
  "Functionality for setting up an event server and poster.  This uses ZeroMQ's
   push/pull socket types in the background."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [monkey.zmq.common :as mc]
            [zeromq.zmq :as z]))

(defn event-server
  "Starts an event server that can only receive events, it cannot send out any of
   itself."
  [ctx addr handler & [opts]]
  (letfn [(receiver [s]
            (let [evt (z/receive s)]
              (handler (mc/parse-edn evt))))]
    (-> (mc/->Server ctx addr receiver :pull)
        (merge opts)
        (co/start))))

(defn event-poster
  "Creates an event poster that sends to the given address.  Cannot receive any events."
  [ctx addr]
  (let [s (doto (z/socket ctx :push)
            (z/connect addr))]
    (mc/->Client s #(z/send-str s (pr-str %2)))))

;; Request types
(def req-event 0)
(def req-register 1)
(def req-unregister 2)

(defn- run-broker-server [{:keys [context address running? poll-timeout matches-filter?]
                           :or {poll-timeout 500}}]
  ;; TODO Add support for multiple addresses (e.g. tcp and inproc)
  (let [socket (doto (z/socket context :router)
                 (z/bind address))
        poller (doto (z/poller context 1)
                 (z/register socket :pollin))
        matches-filter? (or matches-filter? (constantly true))

        register-client
        ;; Adds client with filter to state
        (fn [state sock id evt-filter _]
          (log/info "Registering client" id "for filter" evt-filter)
          (update-in state [:listeners evt-filter sock] (fnil conj #{}) id))
        
        unregister-client
        ;; Removes the client from state
        (fn [state sock id evt-filter _]
          (log/info "Unregistering client" id "for filter" evt-filter)
          (update-in state [:listeners evt-filter sock] disj id))

        dispatch-event
        ;; Dispatches event to all interested clients
        (fn [state sock id evt raw]
          (log/info "Dispatching event from" id ":" evt)
          ;; Find all socket/id pairs where the event matches the filter
          (let [socket-ids (->> (:listeners state)
                                (filter (comp (partial matches-filter? evt) first))
                                (map second))]
            (log/debug "Found" (count socket-ids) "matching socket/ids pairs")
            ;; TODO Eliminate duplicates (from multiple matching filters)
            (update state :events (fnil conj []) [raw socket-ids])))

        req-handlers {req-event dispatch-event
                      req-register register-client
                      req-unregister unregister-client}

        handle-incoming
        (fn [state sock]
          (let [[id req payload] (z/receive-all sock)]
            (let [id (String. id)
                  parsed (mc/parse-edn payload)
                  req (aget req 0)
                  h (get req-handlers req)]
              (log/debug "Handling incoming request from id" id ":" req)
              (if h
                (h state sock id parsed payload)
                (do
                  (log/warn "Got invalid request type:" req)
                  state)))))

        post-pending
        (fn [state]
          (let [{:keys [events]} state]
            (when (not-empty events)
              (log/debug "Posting" (count events) "outgoing events")
              (doseq [[e dest] events]
                (doseq [sock-ids dest]
                  (log/debug "Posting to:" (vals sock-ids))
                  (doseq [[sock ids] sock-ids]
                    ;; TODO Only send when socket is able to process the event
                    (doseq [id ids]
                      (z/send-str sock id z/send-more)
                      ;; Event is raw payload
                      (z/send sock e))))))
            (dissoc state :events)))

        recv-incoming
        (fn [state]
          (z/poll poller poll-timeout)
          (cond-> state
            (z/check-poller poller 0 :pollin)
            (handle-incoming socket)))]
    (try
      (reset! running? true)
      ;; State keeps track of registered clients
      (loop [state {}]
        (when (and @running?
                   (not (.. Thread currentThread isInterrupted)))
          ;; TODO Auto-unregister dead clients (use a ping system)
          (-> state
              (post-pending)
              (recv-incoming)
              (recur))))
      (catch Exception ex
        (log/error "Server error:" ex))
      (finally
        (reset! running? false)
        (z/set-linger socket 0)
        (z/close socket)))
    (log/info "Server terminated")))

(defrecord ThreadComponent [run-fn]
  co/Lifecycle
  (start [this]
    (let [t (assoc this :running? (atom false))]
      (assoc t :thread (doto (Thread. #(run-fn t))
                         (.start)))))
  
  (stop [{:keys [thread running?] :as this}]
    (when thread
      (reset! running? false)
      (.interrupt thread)
      (.join thread)))

  java.lang.AutoCloseable
  (close [this]
    (co/stop this)))

(defn broker-server
  "Starts an event broker that can receive incoming events, but also dispatches outgoing
   events back to the clients.  Clients must register for events with a filter.  The filter
   is a user-defined object, that is matched against the events using the `matches-filter?`
   option.  If no matcher is specified, all events are always matched."
  [ctx addr & [{:keys [autostart?]
                :as opts
                :or {autostart? true}}]]
  (cond-> (map->ThreadComponent (assoc opts
                                       :context ctx
                                       :address addr
                                       :run-fn run-broker-server))
    autostart? (co/start)))

(defn- run-sync-client
  [{:keys [id context address handler stream running? poll-timeout]
    :or {poll-timeout 500}}]
  ;; Sockets are not thread save so we must use them in the same thread
  ;; where we create them.
  (let [socket (doto (z/socket context :dealer)
                 (z/set-identity (.getBytes id))
                 (z/connect address))
        poller (doto (z/poller context 1)
                 (z/register socket :pollin))
        take-next
        (fn [] @(ms/try-take! stream 0))

        send-request
        (fn [[req payload :as m]]
          (log/debug "Posting request:" m)
          (z/send socket (byte-array 1 [req]) z/send-more)
          (z/send-str socket (pr-str payload)))

        receive-evt
        (fn []
          (log/debug "Received events at" id)
          (let [recv (z/receive socket)]
            (-> recv
                (mc/parse-edn)
                (handler))))]
    (try
      (reset! running? true)
      (while (and @running?
                  (not (.. Thread currentThread isInterrupted)))
        ;; Send pending outgoing requests
        (loop [m (take-next)]
          (when m
            (send-request m)
            (recur (take-next))))
        ;; Pass received events to the handler
        (z/poll poller poll-timeout)
        (when (z/check-poller poller 0 :pollin)
          (receive-evt)))
      (catch Exception ex
        (log/error "Socket error:" ex))
      (finally
        (reset! running? false)
        (z/set-linger socket 0)
        (z/close socket)))
    (log/info "Client" id "terminated")))

(defn send-request
  "Sends a raw request to the broker"
  [client req]
  (ms/put! (get-in client [:component :stream]) req))

(defn post-event [client evt]
  (send-request client [req-event evt]))

(defrecord BrokerClient [component]
  co/Lifecycle
  (start [this]
    (log/info "Connecting client to" (:address component))
    (assoc this
           :component
           (-> component
               (assoc :run-fn run-sync-client
                      :stream (ms/stream))
               (co/start))))
  
  (stop [{:keys [component] :as this}]
    (log/debug "Stopping client" (:id component))
    (assoc this :component (co/stop component)))

  clojure.lang.IFn
  (invoke [this evt]
    (post-event this evt))
  
  java.lang.AutoCloseable
  (close [this]
    (co/stop this)))

(defn broker-client
  "Connects to an event broker.  Can post and receive events."
  [ctx addr handler & [{:keys [autostart?] :or {autostart? true} :as opts}]]
  (cond->
      (-> (map->ThreadComponent {:context ctx
                                 :address addr
                                 :handler handler
                                 :id (str (random-uuid))})
          (merge opts)
          (->BrokerClient))
    autostart? (co/start)))

(defn register
  "Registers the client to receive events matching the filter.  A client can
   register with multiple filters.  If any of the filters match, the event will
   be received."
  [client evt-filter]
  (send-request client [req-register evt-filter]))

(defn unregister
  "Unregisters to no longer receive events matching the filter."
  [client evt-filter]
  (send-request client [req-unregister evt-filter]))
