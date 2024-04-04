(ns monkey.zmq.events
  "Functionality for setting up an event server and poster.  This uses ZeroMQ's
   push/pull socket types in the background."
  (:require [clojure.tools.logging :as log]
            [clojure.walk :as cw]
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
(def req-disconnect 3)
(def req-register-ack 4) ; Acknowledge client registration

(defn register-client
  "Registers a new client with filter in the state.  The client is identified by the
   socket and its id."
  [state sock id evt-filter _]
  (log/info "Registering client" id "for filter" evt-filter)
  (update-in state [:listeners evt-filter sock] (fnil conj #{}) id))

(defn- prune-listeners
  "Prunes the tree so all entries with empty values are removed"
  [l]
  (cw/postwalk
   (fn [x]
     (if (map? x)
       (->> x
            (remove (comp empty? second))
            (into {}))
       x))
   l))

(defn unregister-client
  "Removes a client registration from the filter.  It removes the id from the
   registrations for the same filter."
  [state sock id evt-filter _]
  (log/info "Unregistering client" id "for filter" evt-filter)
  (-> state
      (update-in [:listeners evt-filter sock] disj id)
      (update :listeners prune-listeners)))

(defn dispatch-event [matches-filter? state sock id evt raw]
  (log/info "Dispatching event from" id ":" evt)
  ;; Find all socket/id pairs where the event matches the filter
  (let [socket-ids (->> (:listeners state)
                        (filter (comp (partial matches-filter? evt) first))
                        (map second)
                        (apply merge-with clojure.set/union))]
    (log/debug "Found" (count socket-ids) "matching socket/ids pairs:" socket-ids)
    (cond-> state
      (not-empty socket-ids) (update :replies (fnil conj []) [[req-event raw] socket-ids]))))

(defn disconnect-client
  "Handles client disconnect request, by removing it from all registrations."
  [state sock id _ _]
  (letfn [(remove-from-socket [s-ids]
            (update s-ids sock disj id))
          (remove-from-filters [[f s]]
            [f (remove-from-socket s)])
          (remove-from-listeners [l]
            (->> (map remove-from-filters l)
                 (into {})))]
    (log/info "Disconnecting client" id)
    (update state :listeners (comp prune-listeners remove-from-listeners))))

(defn- handle-incoming
  "Handles incoming request on the broker"
  [state req-handlers sock]
  (let [[id req payload] (z/receive-all sock)]
    (try
      (let [id (String. id)
            parsed (mc/parse-edn payload)
            req (aget req 0)
            h (get req-handlers req)]
        (log/debug "Handling incoming request from id" id ":" req)
        (if h
          (h state sock id parsed payload)
          (do
            (log/warn "Got invalid request type:" req)
            state)))
      (catch Exception ex
        (log/error "Unable to handle incoming request.  Id: " id ", req:" req ", payload:" (String. payload))))))

(defn post-pending
  "Posts pending events in the state to the specified socket/client id."
  [state]
  (let [{:keys [replies]} state]
    (when (not-empty replies)
      (log/debug "Posting" (count replies) "outgoing replies")
      (doseq [[[req e] sock-ids] replies]
        (log/debug "Posting to:" sock-ids)
        (doseq [[sock ids] sock-ids]
          ;; TODO Only send when socket is able to process the event
          (doseq [id ids]
            (z/send-str sock id z/send-more)
            ;; Send request type
            (z/send sock (byte-array 1 [req]) z/send-more)
            ;; Event is raw payload
            (z/send sock e)))))
    (dissoc state :replies)))

(defn- run-broker-server [{:keys [context addresses stop? poll-timeout matches-filter? state-stream
                                  linger close-context?]
                           :or {poll-timeout 500
                                linger 0
                                close-context? false}}]
  ;; For backwards compatibility we support one or more addresses
  (let [addresses (if (sequential? addresses) addresses [addresses])
        bind-all (fn [s]
                   (doseq [a addresses]
                     (z/bind s a)))
        socket (doto (z/socket context :router)
                 (z/set-linger linger)
                 (bind-all))
        poller (doto (z/poller context 1)
                 (z/register socket :pollin))
        matches-filter? (or matches-filter? (constantly true))

        req-handlers {req-event (partial dispatch-event matches-filter?)
                      req-register register-client
                      req-unregister unregister-client
                      req-disconnect disconnect-client}

        recv-incoming
        (fn [state]
          (z/poll poller poll-timeout)
          (cond-> state
            (z/check-poller poller 0 :pollin)
            (handle-incoming req-handlers socket)))

        publish-state
        (fn [state]
          (ms/put! state-stream state)
          state)]
    (try
      (log/info "Starting event broker server at addresses:" addresses)
      ;; State keeps track of registered clients
      (loop [state {}]
        (when-not (or @stop?
                      (.. Thread currentThread isInterrupted))
          ;; TODO Auto-unregister dead clients (use a ping system)
          (-> state
              (post-pending)
              (recv-incoming)
              (publish-state)
              (recur))))
      (catch Exception ex
        (log/error "Server error:" ex))
      (finally
        (log/debug "Closing server socket")
        (z/close socket)
        (ms/close! state-stream)
        (when close-context?
          (log/debug "Closing server context")
          (.close context))))
    (log/info "Server terminated")))

(defrecord ThreadComponent [run-fn]
  co/Lifecycle
  (start [this]
    (let [t (assoc this :stop? (atom false))]
      (assoc t :thread (doto (Thread. #(run-fn t))
                         (.start)))))
  
  (stop [{:keys [thread stop?] :as this}]
    (if thread
      (do
        (reset! stop? true)
        (.join thread))
      (log/warn "Not stopping thread component, no thread registered (has it been started?)")))

  java.lang.AutoCloseable
  (close [this]
    (co/stop this)))

(defn- component-running? [{:keys [thread]}]
  (true? (some-> thread (.isAlive))))

(defn broker-server
  "Starts an event broker that can receive incoming events, but also dispatches outgoing
   events back to the clients.  Clients must register for events with a filter.  The filter
   is a user-defined object, that is matched against the events using the `matches-filter?`
   option.  If no matcher is specified, all events are always matched.  The server also
   provides a `state-stream` that holds the latest state, useful for metrics and inspection."
  [ctx addr & [{:keys [autostart?]
                :as opts
                :or {autostart? true}}]]
  (cond-> (map->ThreadComponent (assoc opts
                                       :context ctx
                                       :addresses addr
                                       :state-stream (ms/sliding-stream 1)
                                       :run-fn run-broker-server))
    autostart? (co/start)))

(defn server-running? [s]
  (component-running? s))

(defn- run-sync-client
  [{:keys [id context address handler stream stop? poll-timeout linger close-context?]
    :or {poll-timeout 500 linger 0 close-context? false}}]
  ;; Sockets are not thread safe so we must use them in the same thread
  ;; where we create them.
  (let [socket (doto (z/socket context :dealer)
                 (z/set-identity (.getBytes id))
                 (z/set-linger linger)
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
          (log/debug "Received messages at" id)
          (let [[req recv] (z/receive-all socket)]
            (let [req (aget req 0)]
              (when (= req-event req)
                ;; TODO Instead of passing all events to a single handler, allow a handler per
                ;; registered event filter.  This would mean the broker will have to send back
                ;; the filter (or some id?) that passed the event.
                (-> recv
                    (mc/parse-edn)
                    (handler))))))

        check-stop
        (fn []
          (or @stop?
              (.. Thread currentThread isInterrupted)))

        send-outgoing
        (fn []
          (loop [m (take-next)]
            (when m
              (send-request m)
              (recur (take-next)))))

        read-incoming
        (fn []
          (z/poll poller poll-timeout)
          (when (z/check-poller poller 0 :pollin)
            (receive-evt)))]
    (try
      (loop [s? (check-stop)]
        ;; Send pending outgoing requests
        (send-outgoing)
        ;; When stopped, add a disconnect request
        (when s?
          (log/debug "Sending disconnect request")
          (ms/close! stream)            ; Stop accepting more requests
          (send-request [req-disconnect {}]))
        ;; Check for incoming data
        (read-incoming)
        (when-not s?
          (recur (check-stop))))
      (catch Exception ex
        (log/error "Socket error:" ex))
      (finally
        (log/debug "Closing client socket")
        (z/close socket)
        (when close-context?
          (log/debug "Closing client context")
          (.close context))))
    (log/info "Client" id "terminated")))

(defn send-request
  "Sends a raw request to the broker.  Returns a deferred that realizes when the request
   has been accepted by the subsystem (not necessarily when it's transmitted)."
  [client req]
  (ms/put! (get-in client [:component :stream]) req))

(defn post-event
  "Posts event, returns a deferred that realizes when the event has been accepted
   by the background thread."
  [client evt]
  (send-request client [req-event evt]))

(defrecord BrokerClient [component]
  co/Lifecycle
  (start [this]
    (log/info "Connecting client to" (:address component))
    (assoc this
           :started? true
           :component
           (-> component
               (assoc :run-fn run-sync-client
                      :stream (ms/stream))
               (co/start))))
  
  (stop [{:keys [component started?] :as this}]
    (if started?
      (do 
        (log/debug "Stopping client" (:id component))
        (assoc this :component (co/stop component)))
      (do
        (log/warn "Unable to stop client, it's not started:" this)
        this)))

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

(defn client-running? [c]
  (component-running? (:component c)))

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
