;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.team-container-test
  "`team-container*` renders the same hooks with and without a team id.
  React throws when the hook count changes between two renders of the
  same component, so these tests mount it and render it again with each
  id of the sequence; the documents come from jsdom since the runner has
  no DOM. The guard that compares the route id with the store team is
  covered too: it decides whether the children are painted at all."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react-dom" :as rd]
   ["react-dom/client" :as rdc]
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [app.main.ui :as ui]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private team-id (uuid/next))

(defn- next-immediate
  "Resolves after the tasks React's scheduler queued with `setImmediate`
  so far have run."
  []
  (js/Promise. (fn [resolve] (js/setImmediate resolve))))

(defn- ^:async render-with
  "Mounts `team-container*` once, with `team` as the store's team, and
  renders it again with each of `team-ids`, in order. Resolves to
  `{:renders [...] :console-errors [...]}`: one entry per render, holding
  the markup the document had right after that render and the errors
  React reported up to that point, plus everything React logged to
  `console.error` while the sequence ran.

  Per render matters: a render error makes React drop the subtree, so
  reading the document once at the end tells `renders nothing on purpose`
  and `rendered and then crashed` apart from the errors alone.

  The console is part of the evidence because a hook-order violation is
  reported there and as an internal React error, not through the root's
  error callbacks: React logs it and recovers, so the callbacks stay
  empty and the markup looks right.

  react-dom reads the global `window` while it renders, and its scheduler
  does so again on a later turn, so the jsdom one stays installed until
  the root is unmounted and the scheduler has run."
  [team team-ids]
  (let [errors         (volatile! [])
        rendered       (volatile! [])
        console-errors (volatile! [])
        on-error       (fn [error _info] (vswap! errors conj error))
        window         (.-window (JSDOM. "<!doctype html><div id=\"root\"></div>"))
        container      (.getElementById (.-document window) "root")
        root           (rdc/createRoot container #js {:onUncaughtError on-error
                                                      :onCaughtError on-error
                                                      :onRecoverableError on-error})
        child          (mf/html [:span "inside"])
        previous       (.-window js/globalThis)
        console        (.-console js/globalThis)
        console-error  (.-error console)
        torn-down      (volatile! false)
        teardown       (fn []
                         (when-not @torn-down
                           (vreset! torn-down true)
                           (rd/flushSync #(.unmount root))
                           (.close window)))]
    (set! (.-window js/globalThis) window)
    (set! (.-error console)
          (fn [& args] (vswap! console-errors conj (str/join " " (mapv str args)))))
    (try
      ;; The root goes down from `finally` even when a render throws, and
      ;; before the scheduler turn that closes the window behind it.
      (try
        ;; `with-redefs` restores the vars when the block ends, so every
        ;; render under it must be synchronous. `flushSync` is; a promise
        ;; is not, and defers past the restore.
        (with-redefs [refs/team (l/atom team)
                      mf/useEffect (fn [_f _deps] (mf/use-ref nil))]
          (doseq [id team-ids]
            (rd/flushSync
             (fn []
               (.render root (mf/element (deref #'ui/team-container*)
                                         #js {:teamId id :children child}))))
            (vswap! rendered conj {:markup (.-innerHTML container)
                                   :errors @errors})))
        (finally
          (teardown)))
      (await (next-immediate))
      (finally
        (set! (.-error console) console-error)
        (set! (.-window js/globalThis) previous)))
    {:renders @rendered
     :console-errors @console-errors}))

(defn- messages
  [result]
  (mapv #(.-message %) (:errors result)))

(t/deftest ^:async team-id-arriving-after-the-first-render-keeps-the-hooks
  (let [{:keys [renders console-errors]}
        (await (render-with {:id team-id :permissions {:can-edit true}}
                            [nil team-id]))]
    (t/is (empty? console-errors))
    (t/is (every? #(empty? (messages %)) renders))
    (t/is (str/includes? (:markup (last renders)) "inside"))))

(t/deftest ^:async team-id-leaving-after-the-first-render-keeps-the-hooks
  ;; This direction is not what React flags: a render that calls no hooks
  ;; at all slips through its bookkeeping, so the guard is covered here
  ;; and the hook regression is the `nil` -> uuid case above.
  (let [{:keys [renders console-errors]}
        (await (render-with {:id team-id :permissions {:can-edit true}}
                            [team-id nil]))]
    (t/is (empty? console-errors))
    (t/is (every? #(empty? (messages %)) renders))
    (t/is (str/includes? (:markup (first renders)) "inside"))
    ;; The guard drops the children on purpose, so the second render is
    ;; empty for its own reason and not because React removed the subtree.
    (t/is (= "" (:markup (last renders))))))

(t/deftest ^:async team-container-renders-nothing-when-the-route-team-is-not-the-store-team
  ;; A valid uuid that does not match the team held in the store must not
  ;; paint: the providers would otherwise wrap another team's children.
  (let [{:keys [renders console-errors]}
        (await (render-with {:id (uuid/next) :permissions {:can-edit true}}
                            [team-id]))
        result (last renders)]
    (t/is (empty? console-errors))
    (t/is (empty? (messages result)))
    (t/is (= "" (:markup result)))))

(t/deftest ^:async team-container-renders-the-team-when-the-profile-has-no-permissions
  (let [{:keys [renders console-errors]}
        (await (render-with {:id team-id :permissions nil}
                            [team-id]))
        result (last renders)]
    (t/is (empty? console-errors))
    (t/is (empty? (messages result)))
    (t/is (str/includes? (:markup result) "inside"))))
