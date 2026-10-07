;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.util-events-test
  "The events subsystem: a tap delivers to whatever sink is bound, a
  function inline on the same thread or a channel for a listener of its
  own, and untracked runs pay nothing."
  (:require
   [app.util.events :as events]
   [clojure.test :as t]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(t/deftest tap-outside-a-run-is-a-no-op
  (t/testing "a tap with no sink bound does nothing"
    (t/is (nil? (events/tap :one 1)))))

(t/deftest tap-calls-a-function-sink-inline
  (let [seen (atom [])]
    (binding [events/*sink* (fn [event] (swap! seen conj event))]
      (t/testing "every tap reaches the function, in order"
        (t/is (nil? (events/tap :one 1)))
        (t/is (nil? (events/tap :two 2)))
        (t/is (= [[:one 1] [:two 2]] @seen))))))

(t/deftest tap-puts-on-a-channel-sink
  (let [channel (sp/chan :buf 4)]
    (try
      (binding [events/*sink* channel]
        (t/testing "a tap puts the event on the channel"
          (t/is (nil? (events/tap :one 1)))
          (t/is (= [:one 1] (sp/take! channel)))))
      (finally
        (sp/close! channel)))))

(t/deftest an-exception-from-a-function-sink-propagates
  (let [cause (ex-info "the job is no longer active"
                       {:type :interrupt :code :job-interrupted})]
    (binding [events/*sink* (fn [_event] (throw cause))]
      (t/testing "the tap raises the very exception the sink threw"
        (t/is (identical? cause
                          (try (events/tap :one 1) nil
                               (catch Throwable raised raised))))))))

(t/deftest spawn-listener-consumes-until-the-channel-closes
  (let [seen     (atom [])
        ended    (promise)
        channel  (sp/chan :buf 4)
        listener (events/spawn-listener
                  channel
                  (fn [event] (swap! seen conj event))
                  (fn [] (deliver ended true)))]
    (sp/put! channel [:one 1])
    (sp/put! channel [:two 2])
    (sp/close! channel)
    (px/await! listener)
    (t/testing "the listener saw every event, in order"
      (t/is (= [[:one 1] [:two 2]] @seen)))
    (t/testing "and the close hook ran"
      (t/is (true? (deref ended 1000 false))))))

(t/deftest spawn-listener-stops-on-a-consumer-failure
  (let [seen     (atom [])
        channel  (sp/chan :buf 4)
        listener (events/spawn-listener
                  channel
                  (fn [_event] (throw (ex-info "boom" {})))
                  (fn [] nil))]
    (sp/put! channel [:one 1])
    (sp/put! channel [:two 2])
    (px/await! listener)
    (t/testing "the failure stops the listener, so nothing is consumed"
      (t/is (= [] @seen)))
    (sp/close! channel)))
