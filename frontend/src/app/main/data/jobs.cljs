;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.jobs
  "Following a job the client created.

  A running job publishes its progress as `:job-event` messages on the
  websocket of the profile and keeps its outcome in its own row. This
  namespace joins both: `watch-job` emits every event of the job and one
  final outcome, read from the row and not from the event, because the
  closing event carries only the outcome."
  (:require
   [app.main.repo :as rp]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]))

(def ^:private terminal-statuses
  #{"completed" "failed" "cancelled"})

(defn- events-of
  "The events of one job, as they arrive on the websocket, with the
  milestone of the event under `:payload`."
  [ws-conn job-id]
  (->> (ws/get-rcv-stream ws-conn)
       (rx/filter ws/message-event?)
       (rx/map :payload)
       (rx/filter #(and (= :job-event (:type %))
                        (= job-id (:job-id %))))
       (rx/map (fn [event]
                 {:kind    (keyword (:kind event))
                  :payload (:payload event)}))))

(defn- outcome-of
  "The row of a job as the outcome of the stream, nil while it is not
  over. A job that failed answers with the public error of the row."
  [job]
  (when (contains? terminal-statuses (:status job))
    (cond-> {:status (:status job)}
      (some? (:result job))
      (assoc :result (:result job))

      (some? (:error job))
      (assoc :error (:error job)))))

(defn- read-outcome
  [job-id]
  (->> (rp/cmd! :get-job {:id job-id})
       (rx/map outcome-of)
       (rx/filter some?)))

(defn watch-job
  "The lifecycle of `job-id` as an observable: every event it publishes
  and one final outcome, after which the stream ends.

  The outcome is read from the row and not from the closing event: it is
  read once when the stream starts and again on every reconnect of the
  websocket, so a job that ended before this stream existed, or while the
  socket was down, still answers. A read that fails reaches the caller,
  which is the only one that can tell a transient failure from a job that
  is gone.

  Emissions:

  - `{:kind :progress :payload {:stage ... :counters {...}}}` per event,
    with the kind of the event (`:progress`, `:start`, `:retry`)
  - `{:status \"completed\" :result {...}}` or `{:status \"failed\"
    :error {...}}` or `{:status \"cancelled\"}` once, and then the
    stream completes"
  [ws-conn job-id]
  (let [events   (events-of ws-conn job-id)
        progress (->> events
                      (rx/filter #(not= :end (:kind %))))
        triggers (rx/merge (->> (ws/get-rcv-stream ws-conn)
                                (rx/filter ws/opened-event?))
                           (->> events
                                (rx/filter #(= :end (:kind %)))))
        outcomes (->> (rx/merge (rx/of ::start) triggers)
                      (rx/mapcat (fn [_] (read-outcome job-id)))
                      (rx/take 1)
                      (rx/share))]
    (rx/merge (rx/take-until outcomes progress)
              outcomes)))

(defn cancel-job
  "Ask the server to cancel a job the caller created. Fire and forget:
  failures are ignored because the job may have just finished on its
  own."
  [job-id]
  (->> (rp/cmd! :cancel-job {:id job-id})
       (rx/catch (fn [_] (rx/of nil)))
       (rx/subs! (constantly nil) (constantly nil) (constantly nil))))
