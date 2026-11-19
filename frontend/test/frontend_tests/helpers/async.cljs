;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.helpers.async
  "Async-first plumbing for ClojureScript tests.

  Lets a test read sequentially while presupposing asynchronous APIs:
  triggers run, effects are awaited as promises, and `done` always runs
  at the end. Relies on two platform properties:

  - `cljs.test` keeps its env in a `set!` var, so assertions inside
    deferred ticks are still counted.
  - RxJS delivers `observe-on :async` notifications in scheduling (FIFO)
    order, so awaiting the last scheduled delivery observes every effect
    scheduled before it."
  (:require
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(defn ->promise
  "Coerces a single-value observable into a js/Promise that resolves
  with its first value (or rejects on error)."
  [ob]
  (js/Promise. (fn [resolve reject] (rx/subs! resolve reject ob))))

(defn ^:async await-response
  "Pushes `value` into the `response` subject and resolves once the last
  recorded request completes, delivery included.

  The promise subscribes BEFORE pushing: subscribing after the push
  would never resolve. Request entries are maps holding the request
  observable under `:req`."
  [requests response value]
  (let [p (->promise (:req (last @requests)))]
    (rx/push! response value)
    (await p)))

(defn settle
  "Resolves on the next macrotask, letting scheduled deliveries land.
  Await it before asserting absence: silence is only meaningful once the
  queue had a chance to deliver."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn observe
  "Subscribes to `ob` forcing asynchronous delivery through `observe-on
  :async` — even when the source is synchronous — and returns a promise
  resolving once the stream terminates.

  Keyword args: `:on-next` per value, `:on-error` (handled errors resolve;
  unhandled reject), `:on-complete` on clean termination, `:timeout-ms`
  (default 2000). Asserts the stream is provided: resolving nil would let
  a test pass without observing anything. Rejects on timeout instead of
  hanging."
  [ob & {:keys [on-next on-error on-complete timeout-ms]
         :or {timeout-ms 2000}}]
  (assert (some? ob) "observe requires an observable stream")
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout
                  #(reject (ex-info "Stream did not terminate in time" {}))
                  timeout-ms)
           settle (fn [f v]
                    (js/clearTimeout timer)
                    (f v))]
       (->> ob
            (rx/observe-on :async)
            (rx/subs!
             (fn [v] (when on-next (on-next v)))
             (fn [e]
               (if on-error
                 (do (on-error e) (settle resolve nil))
                 (settle reject e)))
             (fn []
               (when on-complete (on-complete))
               (settle resolve nil))))))))

(def ^:private default-timeout-ms
  "Ceiling of a wait, in real milliseconds. With a signal it only bounds
  the failure: a wait that resolves on an event never reaches it. Without
  a signal it is the poll budget."
  5000)

(defn- ^:async poll
  [pred msg expired?]
  (cond
    (pred)    nil
    @expired? (t/is false (str "Timed out waiting for: " msg))
    :else     (do (await (settle))
                  (await (poll pred msg expired?)))))

(defn- ^:async wait-poll
  [pred msg timeout-ms]
  (let [expired? (atom false)
        timer    (js/setTimeout #(reset! expired? true) timeout-ms)]
    (await (poll pred msg expired?))
    (js/clearTimeout timer)))

(defn- cause-text
  "Readable, bounded text for a signal error: its message when it has one,
  otherwise the printed value inside a `try` (a cyclic value would not print),
  and never more than 500 characters."
  [cause]
  (let [text (or (ex-message cause)
                 (try
                   (pr-str cause)
                   (catch :default _ "#<unprintable>")))]
    (if (> (count text) 500)
      (str (subs text 0 500) "…")
      text)))

(defn- wait-signal
  "Resolves when `pred` holds, re-checking it on every emission of `signal`.

  The timeout only bounds failure: it records the failure and resolves, so
  the caller never hangs. A signal that ends or fails without the predicate
  holding is reported with its own reason. The subscription is disposed on
  every path."
  [pred msg signal timeout-ms]
  (js/Promise.
   (fn [resolve]
     (let [done?  (atom false)
           sub*   (atom nil)
           timer* (atom nil)
           stop   (fn []
                    (when-let [s @sub*] (rx/dispose! s))
                    (when-let [t @timer*] (js/clearTimeout t)))
           fail   (fn [text]
                    (when-not @done?
                      (reset! done? true)
                      (stop)
                      (t/is false text)
                      (resolve nil)))
           finish (fn []
                    (when-not @done?
                      (reset! done? true)
                      (stop)
                      (resolve nil)))]
       (reset! timer*
               (js/setTimeout #(fail (str "Timed out waiting for: " msg))
                              timeout-ms))
       (reset! sub*
               (rx/subs!
                (fn [_] (when (pred) (finish)))
                (fn [cause]
                  (if (pred)
                    (finish)
                    (fail (str "Signal failed while waiting for: " msg ": "
                               (cause-text cause)))))
                (fn []
                  (if (pred)
                    (finish)
                    (fail (str "Signal ended while waiting for: " msg))))
                signal))
       ;; A source that emits while subscribing already settled the wait.
       (when @done?
         (rx/dispose! @sub*))
       nil))))

(defn ^:async wait-for
  "Resolves once `pred` holds, checking immediately and then re-checking it
  on every emission of `:signal` (or once per macrotask when no signal is
  given), up to `:timeout-ms` of real time (default 5000).

  With a signal the ceiling bounds failure only: the wait resolves on the
  event that changes the state, not after a number of event-loop turns.
  Without one it is a poll budget, and turns are not a unit of time (50
  turns are ~54 ms idle and much less ground under load). Records a single
  failure instead of hanging: `Timed out waiting for: <msg>` when the ceiling
  expires, or the signal's own reason when it ends or fails (see
  `wait-signal`)."
  [pred msg & [{:keys [signal timeout-ms]
                :or   {timeout-ms default-timeout-ms}}]]
  (when-not (pred)
    (if (some? signal)
      (await (wait-signal pred msg signal timeout-ms))
      (await (wait-poll pred msg timeout-ms)))))

(defn wait-for-store
  "Resolves once `pred` holds over `store`, re-checking it on every store
  event. The store notifies its subscribers after applying the update, so the
  predicate already sees the new state. One signal only: a predicate that also
  reads another source passes a merged signal to `wait-for` itself."
  [store pred msg]
  (wait-for pred msg {:signal (ptk/input-stream store)}))
