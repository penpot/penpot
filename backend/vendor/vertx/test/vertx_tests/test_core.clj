;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx-tests.test-core
  (:require [clojure.test :as t]
            [vertx.core :as vx]
            [vertx.eventbus :as vxe]))

(def sleep #(Thread/sleep %))

(t/deftest start-stop-verticle
  (with-open [vsm (vx/system)]
    (let [state (atom {})]
      (let [on-start (fn [_] (swap! state assoc :start true) {:a 1})
            on-stop (fn [_ s] (swap! state assoc :stop true :inner (:a s)))
            verticle (vx/verticle {:on-start on-start :on-stop on-stop})]
        ;; Start and stop verticle
        (.close @(vx/deploy! vsm verticle))

        ;; Checks
        (t/is (:start @state))
        (t/is (:stop @state))
        (t/is (= (:inner @state) 1))))))

(t/deftest start-stop-actor
  (with-open [vsm (vx/system)]
    (let [state (atom {})]
      (let [on-start (fn [_] (swap! state assoc :start true) {:a 1})
            on-stop (fn [_ s] (swap! state assoc :stop true :inner (:a s)))
            rcvlock (promise)
            on-message #(deliver rcvlock %2)
            verticle (vx/actor "test.topic" {:on-message on-message
                                             :on-start on-start
                                             :on-stop on-stop})]
        (with-open [vid @(vx/deploy! vsm verticle)]
          ;; Checks
          (t/is (true? (:start @state)))
          (t/is (nil? (:stop @state)))

          (vxe/send! vsm "test.topic" {:num 1})

          ;; Checks
          (t/is (vxe/message? @rcvlock))
          (t/is (= {:num 1} (:body @rcvlock))))

        (t/is (= (:inner @state) 1))
        (t/is (true? (:stop @state)))))))



