;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks-event-consumer-test
  "The generalist of the audit-log event consumer: the registry of
  reactions, the dispatch and the drain of the queue."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.tasks.event-consumer :as event-consumer]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- capture-reaction
  "A fake reaction that keeps the batch it receives."
  [seen events]
  {:id     :test
   :flag   :test-flag
   :events events
   :apply  (fn [_cfg batch] (swap! seen conj batch))})

(defn- append-row
  "Append an audit-log row the way the producer does."
  ([name props] (append-row name props "backend"))
  ([name props source]
   (#'audit/append-audit-entry
    th/*system*
    {:id         (uuid/next)
     :name       name
     :type       "action"
     :source     source
     :profile-id uuid/zero
     :ip-addr    "127.0.0.1"
     :props      props
     :context    {}
     :tracked-at (ct/now)
     :created-at (ct/now)})))

(defn- events-pendings
  "The pendings of the events consumer (id 3)."
  []
  (th/db-exec! ["SELECT audit_log_id FROM audit_log_pending WHERE consumer = 3"]))

(defn- usage-row
  [team-id]
  (th/db-exec-one! ["SELECT team_id, dirty_at FROM team_storage_usage WHERE team_id = ?"
                    team-id]))

(t/deftest dispatch-applies-a-reaction-to-the-events-it-declares
  (let [seen     (atom [])
        reaction (capture-reaction seen #{"delete-file"})
        event-a  {:id (uuid/next) :name "delete-file" :props {}}
        event-b  {:id (uuid/next) :name "create-file" :props {}}]
    (with-redefs [cf/flags #{:test-flag}
                  event-consumer/reactions [reaction]]
      (let [claimed (event-consumer/dispatch-reactions
                     th/*system*
                     [event-a event-b])]
        (t/is (= 1 claimed))
        (t/is (= 1 (count @seen)))
        (t/is (= ["delete-file"] (mapv :name (first @seen))))))))

(t/deftest dispatch-ignores-the-events-no-reaction-claims
  ;; The queue of the consumer is not selective: the events that nobody
  ;; claims are discarded.
  (let [seen     (atom [])
        reaction (capture-reaction seen #{"delete-file"})]
    (with-redefs [cf/flags #{:test-flag}
                  event-consumer/reactions [reaction]]
      (let [claimed (event-consumer/dispatch-reactions
                     th/*system*
                     [{:id (uuid/next) :name "create-file" :props {}}])]
        (t/is (= 0 claimed))
        (t/is (empty? @seen))))))

(t/deftest dispatch-skips-a-reaction-whose-flag-is-off
  ;; The flag of the reaction is the one that turns it on: without it,
  ;; the event is not claimed and the reaction does not run.
  (let [seen     (atom [])
        reaction (capture-reaction seen #{"delete-file"})]
    (with-redefs [cf/flags #{}
                  event-consumer/reactions [reaction]]
      (let [claimed (event-consumer/dispatch-reactions
                     th/*system*
                     [{:id (uuid/next) :name "delete-file" :props {}}])]
        (t/is (= 0 claimed))
        (t/is (empty? @seen))))))

(t/deftest dispatch-gives-each-reaction-only-its-own-events
  ;; Two reactions that declare the same name: each one receives its
  ;; batch and the event is counted once.
  (let [seen-a     (atom [])
        seen-b     (atom [])
        reaction-a (capture-reaction seen-a #{"delete-file"})
        reaction-b (capture-reaction seen-b #{"delete-file" "create-file"})
        events     [{:id (uuid/next) :name "delete-file" :props {}}
                    {:id (uuid/next) :name "create-file" :props {}}]]
    (with-redefs [cf/flags #{:test-flag}
                  event-consumer/reactions [reaction-a reaction-b]]
      (let [claimed (event-consumer/dispatch-reactions th/*system* events)]
        (t/is (= 2 claimed))
        (t/is (= ["delete-file"] (mapv :name (first @seen-a))))
        (t/is (= ["delete-file" "create-file"] (mapv :name (first @seen-b))))))))

(t/deftest events-consumer-drains-the-queue-and-marks-the-team-dirty
  ;; The whole path: the producer queues the event, the consumer drains
  ;; it and the reaction marks the team dirty.
  (let [prof (th/create-profile* 41 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags #{:audit-log :event-consumer :storage-usage}]
      (append-row "upload-file-media-object" {:team-id team})
      (t/is (= 1 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 0 (count (events-pendings))))
      (t/is (some? (:dirty-at (usage-row team)))))))

(t/deftest events-consumer-discards-the-events-nobody-claims
  ;; An event that no reaction claims is drained and dropped: the queue
  ;; takes the whole stream, but nobody works on it.
  (let [prof (th/create-profile* 42 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags #{:audit-log :event-consumer :storage-usage}]
      (append-row "navigate" {:route "dashboard"})
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 0 (count (events-pendings))))
      (t/is (nil? (:dirty-at (usage-row team)))))))

(t/deftest events-consumer-does-nothing-without-its-flag
  ;; Without its flag the consumer does not even queue: the producer
  ;; never hands it the stream.
  (let [prof (th/create-profile* 43 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags #{:audit-log :storage-usage}]
      (append-row "upload-file-media-object" {:team-id team})
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 0 (count (events-pendings))))
      (t/is (nil? (:dirty-at (usage-row team)))))))

(t/deftest events-consumer-does-nothing-without-the-reaction-flag
  ;; The queue takes the event, but the reaction that would work on it
  ;; is off: the event is drained and dropped.
  (let [prof (th/create-profile* 44 {:is-active true})
        team (:default-team-id prof)]
    (with-redefs [cf/flags #{:audit-log :event-consumer}]
      (append-row "upload-file-media-object" {:team-id team})
      (t/is (= 1 (count (events-pendings))))
      (th/run-task! :audit-log-consumer-events {})
      (t/is (= 0 (count (events-pendings))))
      (t/is (nil? (:dirty-at (usage-row team)))))))
