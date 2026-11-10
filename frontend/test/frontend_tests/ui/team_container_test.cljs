;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.team-container-test
  "`team-container*` renders the same hooks with and without a team id.
  React throws when the hook count changes between two renders of the
  same component, so these tests mount it and render it again; the
  documents come from jsdom since the runner has no DOM."
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
  "Mounts `team-container*` once and renders it again with each of
  `team-ids`, in order. Resolves to the errors React reported and the
  markup left in the document.

  react-dom reads the global `window` while it renders, and its scheduler
  does so again on a later turn, so the jsdom one stays installed until
  the root is unmounted and the scheduler has run.

  The effect is replaced by a hook that does nothing: the real one raises
  for an invalid id, and what is under test is the render. It must still
  take a hook slot, because React only compares the hooks of two renders
  when the first one had any."
  [team-ids]
  (let [errors    (volatile! [])
        on-error  (fn [error _info] (vswap! errors conj error))
        window    (.-window (JSDOM. "<!doctype html><div id=\"root\"></div>"))
        container (.getElementById (.-document window) "root")
        root      (rdc/createRoot container #js {:onUncaughtError on-error
                                                 :onCaughtError on-error
                                                 :onRecoverableError on-error})
        child     (mf/html [:span "inside"])
        previous  (.-window js/globalThis)]
    (set! (.-window js/globalThis) window)
    (try
      (with-redefs [refs/team (l/atom {:id team-id :permissions {:can-edit true}})
                    mf/useEffect (fn [_f _deps] (mf/use-ref nil))]
        (doseq [id team-ids]
          (rd/flushSync
           (fn []
             (.render root (mf/element (deref #'ui/team-container*)
                                       #js {:teamId id :children child}))))))
      (let [markup (.-innerHTML container)]
        (rd/flushSync #(.unmount root))
        (await (next-immediate))
        {:errors @errors
         :markup markup})
      (finally
        (set! (.-window js/globalThis) previous)))))

(t/deftest ^:async team-id-arriving-after-the-first-render-keeps-the-hooks
  (let [{:keys [errors markup]} (await (render-with [nil team-id]))]
    (t/is (empty? (map #(.-message %) errors)))
    (t/is (str/includes? markup "inside"))))

(t/deftest ^:async team-id-leaving-after-the-first-render-keeps-the-hooks
  (let [{:keys [errors markup]} (await (render-with [team-id nil]))]
    (t/is (empty? (map #(.-message %) errors)))
    (t/is (= "" markup))))
