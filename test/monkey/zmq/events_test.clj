(ns monkey.zmq.events-test
  (:require [clojure.test :refer [deftest testing is]]
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
  (let [ctx (z/context 1)
        opts {:poll-timeout 100}]
    
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
        (is (close-all [client server]))))))
