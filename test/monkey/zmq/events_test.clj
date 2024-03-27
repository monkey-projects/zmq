(ns monkey.zmq.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.stream :as ms]
            [monkey.zmq
             [common :refer [close-all]]
             [events :as sut]]
            [zeromq.zmq :as z]))

(defn wait-for [p & [timeout]]
  (let [s (System/currentTimeMillis)]
    (loop [n s
           v (p)]
      (if (> n (+ s (or timeout 1000)))
        ::timeout
        (if-not v
          (do
            (Thread/sleep 10)
            (recur (System/currentTimeMillis)
                   (p)))
          v)))))

(def opts {:poll-timeout 100})

(defn- random-addr []
  (str "inproc://" (random-uuid)))

(deftest event-server
  (testing "passes received events to handler"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (random-addr)
          e (sut/event-server ctx addr handler opts)
          p (sut/event-poster ctx addr)]
      (is (some? e))
      (is (ifn? p))
      (is (true? (p "test event")))
      (is (not= ::timeout (wait-for #(not-empty @recv))))
      (is (= ["test event"] @recv))
      (is (close-all [p e ctx]))))
 
  (testing "can post multiple events from one source"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (random-addr)
          e (sut/event-server ctx addr handler opts)
          p (sut/event-poster ctx addr)
          n 10]
      (is (->> (range n)
               (map (comp p (partial str "Event ")))
               (every? true?)))
      (is (not= ::timeout (wait-for #(= n (count @recv)))))
      (is (= "Event 0" (first @recv)))
      (is (close-all [p e ctx]))))

  (testing "can post events from multiple sources"
    (let [ctx (z/context 1)
          recv (atom [])
          handler (partial swap! recv conj)
          addr (random-addr)
          e (sut/event-server ctx addr handler opts)
          n 10
          p (doall (repeatedly n #(sut/event-poster ctx addr)))]
      (is (->> p
               (map-indexed (fn [i poster]
                              (poster (str "Event " i))))
               (every? true?)))
      (is (not= ::timeout (wait-for #(= n (count @recv)))))
      (is (close-all (concat p [e ctx]))))))

(deftest event-broker
  (let [opts {:poll-timeout 100 :linger 0}]

    (with-open [ctx (z/context 1)]
      
      (testing "clients receive events sent by other clients"
        (let [addr (random-addr)
              received (atom [])
              server (sut/broker-server ctx addr opts)
              a (sut/broker-client ctx addr (constantly nil)
                                   (assoc opts :id "client-a"))
              b (sut/broker-client ctx addr (partial swap! received conj)
                                   (assoc opts :id "client-b"))
              evt {:type :test-event
                   :message "test event"}]
          (is (some? (sut/register b (:type evt))))
          (is (not= ::timeout (wait-for (fn []
                                          ;; Keep sending events until timeout or one is received
                                          (a evt)
                                          (not-empty @received)))))
          (is (= evt (first @received)))
          (is (close-all [a b server]))))

      (testing "clients receive their own events"
        (let [addr (random-addr)
              received (atom [])
              server (sut/broker-server ctx addr opts)
              client (sut/broker-client ctx addr (partial swap! received conj) opts)
              evt {:type :test-event
                   :message "test event"}]
          ;; Since we're sending and receiving from the same client there is no need to retry
          (is (some? (sut/register client (:type evt))))
          (is (some? (client evt)))
          (is (not= ::timeout (wait-for #(not-empty @received))))
          (is (= evt (first @received)))
          (is (close-all [client server]))))

      (testing "clients don't receive events not allowed by filter"
        (let [addr (random-addr)
              received (atom [])
              server (sut/broker-server ctx addr (assoc opts :matches-filter? (fn [evt ef]
                                                                                (= (:type evt) ef))))
              client (sut/broker-client ctx addr (partial swap! received conj) opts)
              evt {:type :test-event
                   :message "test event"}]
          (is (some? (sut/register client :other-type)))
          (is (some? (client evt)))
          (is (= ::timeout (wait-for #(not-empty @received) 200)))
          (is (close-all [client server]))))

      (testing "when mulitiple subscriptions, receive event only once"
        (let [addr (random-addr)
              received (atom [])
              server (sut/broker-server ctx addr opts)
              client (sut/broker-client ctx addr (partial swap! received conj) opts)
              evt {:type :test-event
                   :message "test event"}]
          (is (some? (sut/register client (:type evt))))
          (is (some? (sut/register client (:type evt))))
          (is (some? (client evt)))
          (is (not= ::timeout (wait-for #(not-empty @received))))
          (is (= [evt] @received))
          (is (close-all [client server]))))

      (testing "clients can unsubscribe"
        (let [addr (random-addr)
              received (atom [])
              server (sut/broker-server ctx addr opts)
              client (sut/broker-client ctx addr (partial swap! received conj) opts)
              evt {:type :test-event
                   :message "test event"}
              ef (:type evt)]
          (is (some? (sut/register client ef)))
          (is (some? (client evt)))
          (is (some? (sut/unregister client ef)))
          (is (some? (client evt)))
          (is (not= ::timeout (wait-for #(not-empty @received))))
          (is (= [evt] @received) "Only one received event was expected")
          (is (close-all [client server]))))

      (testing "when client closes, unregisters from all"
        (let [addr (random-addr)
              received (atom [])
              server (sut/broker-server ctx addr opts)
              client (sut/broker-client ctx addr (partial swap! received conj) opts)
              ss (:state-stream server)
              evt {:type :test-event
                   :message "test event"}
              ef (:type evt)
              fs (ms/filter (comp empty? :listeners) ss)]
          (is (some? (sut/register client ef)))
          (is (some? (client evt)))
          (is (nil? (.close client)))
          (let [state (deref (ms/take! ss) 1000 :timeout)]
            (is (not= ::timeout state)))
          (is (nil? (.close server))))))

    (testing "setting linger will block context closing until all has been sent"
      (let [addr (random-addr)
            ctx (z/context 1)
            opts (assoc opts :context ctx :linger 1000)
            received (atom [])
            server (sut/broker-server ctx addr (assoc opts :close-context? true))
            client (sut/broker-client ctx addr (partial swap! received conj) opts)
            evt {:type :test-event
                 :message "test event"}
            ef (:type evt)]
        (is (some? (sut/register client ef)))
        (is (some? (client evt)))
        (is (close-all [client server]))
        (is (not= ::timeout (wait-for #(not-empty @received))))))

    (testing "can listen on multiple addresses"
      (let [addrs (repeatedly 2 random-addr)
            ctx (z/context 1)
            opts (assoc opts :context ctx :linger 1000)
            received (atom {})
            server (sut/broker-server ctx addrs (assoc opts :close-context? true))
            clients (map (fn [addr]
                           (sut/broker-client ctx addr (partial swap! received update addr (fnil conj [])) opts))
                         addrs)
            evt {:type ::test-type}
            a (assoc evt :message "first event")
            b (assoc evt :message "second event")
            ef (:type evt)]
        (is (some? (sut/register (first clients) ef)))
        (is (some? (sut/register (second clients) ef)))
        (is (some? ((first clients) a)))
        (is (some? ((second clients) b)))
        (is (not= ::timeout (wait-for #(and (not-empty @received)
                                            (every? (comp (partial = 2) count) (vals @received))))))
        (is (close-all (concat clients [server])))
        (is (= #{a b} (-> @received (get (first addrs)) set)))
        (is (= #{a b} (-> @received (get (second addrs)) set)))))

    (testing "when multiple addresses, dispatches to the correct registered client"
      (let [addrs (repeatedly 2 random-addr)
            ctx (z/context 1)
            opts (assoc opts :context ctx :linger 1000)
            received (atom {})
            server (sut/broker-server ctx addrs (assoc opts
                                                       :close-context? true
                                                       :matches-filter? (fn [evt ef]
                                                                          (= (:type evt) ef))))
            clients (map (fn [addr]
                           (sut/broker-client ctx addr (partial swap! received update addr (fnil conj [])) opts))
                         addrs)
            a {:type ::first-type}
            b {:type ::second-type}]
        (is (some? (sut/register (first clients) ::first-type)))
        (is (some? (sut/register (second clients) ::second-type)))
        (is (some? ((first clients) b)))
        (is (some? ((second clients) a)))
        (is (not= ::timeout (wait-for #(and (not-empty @received)
                                            (every? not-empty (vals @received))))))
        (is (close-all (concat clients [server])))
        (is (= {(first addrs) [a]
                (second addrs) [b]}
               @received))))))

(deftest disconnect-client
  (testing "removes client registrations from state"
    (let [state (sut/register-client {} ::socket ::id ::filter nil)]
      (is (not-empty (:listeners state)))
      (is (empty? (-> state
                      (sut/disconnect-client ::socket ::id {} nil)
                      :listeners)))))

  (testing "leaves other registrations in place"
    (let [state (-> {}
                    (sut/register-client ::socket ::first ::first-filter nil)
                    (sut/register-client ::socket ::second ::second-filter nil)
                    (sut/disconnect-client ::socket ::first {} nil))]
      (is (= {::second-filter {::socket #{::second}}}
             (:listeners state))))))

(deftest dispatch-event
  (testing "with same socket, adds events to dispatch to replies according to filter"
    (let [matcher (fn [evt ef]
                    (= (:type evt) (:type ef)))
          state (-> {}
                    (sut/register-client ::socket ::first {:type ::filter-1} nil)
                    (sut/register-client ::socket ::second {:type ::filter-2} nil)
                    (as-> x (sut/dispatch-event matcher x ::socket ::third {:type ::filter-1} ::raw)))]
      (is (= 1 (count (:replies state))))
      (is (= [0 ::raw] (ffirst (:replies state))) "send event request with raw payload")
      (is (= [{::socket #{::first}}] (-> state :replies first second))))))

(deftest unregister-client
  (testing "removes client id from filter, leaves others in place"
    (let [state (-> {}
                    (sut/register-client ::socket-a ::first ::first-filter nil)
                    (sut/register-client ::socket-a ::second ::first-filter nil)
                    (sut/register-client ::socket-b ::third ::second-filter nil)
                    (sut/unregister-client ::socket-a ::first ::first-filter nil))]
      (is (= {::first-filter
              {::socket-a #{::second}}
              ::second-filter
              {::socket-b #{::third}}}
             (:listeners state)))))

  (testing "prunes listener tree"
    (let [state (-> {}
                    (sut/register-client ::socket-a ::first ::first-filter nil)
                    (sut/register-client ::socket-b ::second ::second-filter nil)
                    (sut/unregister-client ::socket-a ::first ::first-filter nil))]
      (is (= {::second-filter
              {::socket-b #{::second}}}
             (:listeners state))))))
