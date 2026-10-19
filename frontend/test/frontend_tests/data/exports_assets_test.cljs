;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-assets-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.exports.assets :as de]
   [app.main.data.persistence :as dwp]
   [app.main.errors :as errors]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.events :as the]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private export {:id (uuid/next)
                       :object-id (uuid/next)
                       :type :png
                       :suffix ""
                       :scale 1})

(defn- export-with-name
  [name]
  (merge export {:name name}))

(defn- test-state
  []
  {:profile-id (:id export)
   :ws-conn nil})

(t/deftest normalize-export-preserves-existing-name
  (t/is (= (export-with-name "Layer 1")
           (de/normalize-export (export-with-name "Layer 1")))))

(t/deftest normalize-export-replaces-nil-name-with-object-id
  (t/is (= (export-with-name (str (:object-id export)))
           (de/normalize-export (assoc export :name nil)))))

(t/deftest normalize-export-replaces-empty-name-with-object-id
  (t/is (= (export-with-name (str (:object-id export)))
           (de/normalize-export (assoc export :name "")))))

(t/deftest request-simple-export-sends-normalized-export
  (t/async done
    (let [export (export-with-name "")
          observed (atom nil)]
      (mock/with-mocks {repo/cmd! (mock/stub (fn [_ params]
                                               (reset! observed params)
                                               (rx/of {:filename "export.png"
                                                       :mtype "image/png"
                                                       :uri "blob:export"})))
                        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/of ::force-persisted)))
                        dom/trigger-download-uri (mock/stub (fn [& _] nil))}
        (fn [done']
          (let [completed (fn [_state]
                            (t/is (= (export-with-name (str (:object-id export)))
                                     (-> @observed :exports first))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-simple-export {:export export})
                       :the/end)))
        done))))

(t/deftest request-multiple-export-sends-normalized-enabled-exports
  (t/async done
    (let [exports [{:id "enabled-1"
                    :object-id "enabled-1"
                    :shape {:id "enabled-1"}
                    :type :png
                    :suffix ""
                    :scale 1
                    :enabled true
                    :name ""}]
          observed (atom nil)]
      (mock/with-mocks {repo/cmd! (mock/stub (fn [_ params]
                                               (reset! observed params)
                                               (rx/of {:id (:id export)})))
                        ws/get-rcv-stream (mock/stub (fn [_] (rx/empty)))
                        st/ongoing-tasks (atom #{})}
        (fn [done']
          (let [completed (fn [_state]
                            (t/is (= [{:id "enabled-1"
                                       :object-id "enabled-1"
                                       :shape {:id "enabled-1"}
                                       :type :png
                                       :suffix ""
                                       :scale 1
                                       :enabled true
                                       :name "enabled-1"}]
                                     (:exports @observed))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-multiple-export {:exports exports})
                       :the/end)))
        done))))

(defn- export-store
  "A store for the export tests that records store errors in `store-errors`."
  [state store-errors]
  (ptk/store {:state state :on-error #(swap! store-errors conj %)}))

(defn- unsaved-state
  "Test state whose save queue still holds a change of `file-id`."
  [file-id]
  (let [commit-id (uuid/next)]
    (assoc (test-state)
           :persistence {:queue (conj #queue [] commit-id)
                         :index {commit-id {:id commit-id :file-id file-id}}})))

;; Scenario: the exporter fails a single export. Proves: the user gets a
;; toast and stays in the workspace; the failure is not raised to the global
;; error handler, which would replace the workspace with an error page.
(t/deftest ^:async failed-simple-export-shows-a-toast-instead-of-raising
  (let [store-errors (atom [])
        flashed      (atom [])
        store        (export-store (test-state) store-errors)]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/throw (ex-info "exporter failed"
                                                                         {:type :server-error
                                                                          :code :unhandled}))))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        errors/flash                     (fn [& {:keys [hint type]}]
                                           (swap! flashed conj [hint type]))
        i18n/tr                          (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-simple-export {:export export}))
       (await (async/wait-for #(seq @flashed) "the failure is shown"))
       (await (async/settle))
       (t/is (= [["translated:errors.export-failed" :unhandled]] @flashed)
             "an exporter defect reports as unhandled")
       (t/is (empty? @store-errors) "nothing reaches the global error handler")
       (t/is (nil? (:export @store)) "the export state is cleared")))))

;; Scenario: the export request of a multiple export fails. Proves: the user
;; gets a toast, and the leave-page warning the export turned on is released
;; even though no progress message will ever arrive.
(t/deftest ^:async failed-multiple-export-shows-a-toast-and-releases-the-leave-warning
  (let [store-errors (atom [])
        flashed      (atom [])
        tasks        (atom #{})
        store        (export-store (test-state) store-errors)]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/throw (ex-info "exporter failed"
                                                                         {:type :server-error}))))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        ws/get-rcv-stream                (mock/stub (fn [_] (rx/subject)))
        st/ongoing-tasks                 tasks
        errors/flash                     (fn [& {:keys [hint]}] (swap! flashed conj hint))
        i18n/tr                          (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-multiple-export {:exports [(assoc export :enabled true)]}))
       (await (async/wait-for #(seq @flashed) "the failure is shown"))
       (await (async/wait-for #(not (contains? @tasks :export))
                              "the leave-page warning is released"))
       (t/is (= ["translated:errors.export-failed"] @flashed))
       (t/is (empty? @store-errors) "nothing reaches the global error handler")))))

;; Scenario: an export starts while its file has a change not saved yet.
;; Proves: the user is warned that the export may miss it, and the export
;; runs anyway.
(t/deftest ^:async export-warns-when-the-file-has-unsaved-changes
  (let [file-id    (uuid/next)
        downloaded (atom false)
        store      (export-store (unsaved-state file-id) (atom []))]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/of {:filename "export.png"
                                                              :mtype "image/png"
                                                              :uri "blob:export"})))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        dom/trigger-download-uri         (mock/stub (fn [& _] (reset! downloaded true)))
        i18n/tr                          (mock/stub #(str "translated:" %))}
       (ptk/emit! store (de/request-simple-export {:export (assoc export :file-id file-id)}))
       (await (async/wait-for #(true? @downloaded) "the export runs anyway"))
       (t/is (= "translated:workspace.export.unsaved-changes"
                (get-in @store [:notification :content])))
       (t/is (= :warning (get-in @store [:notification :level])))))))

;; Scenario: an export starts with every change of its file saved. Proves:
;; no warning is shown.
(t/deftest ^:async export-shows-no-warning-when-the-file-is-saved
  (let [downloaded (atom false)
        store      (export-store (test-state) (atom []))]
    (await
     (mock/with-mocks*
       {repo/cmd!                        (mock/stub (fn [_ _]
                                                      (rx/of {:filename "export.png"
                                                              :mtype "image/png"
                                                              :uri "blob:export"})))
        dwp/force-persist-files-and-wait (mock/stub (fn [_ _] (rx/empty)))
        dom/trigger-download-uri         (mock/stub (fn [& _] (reset! downloaded true)))}
       (ptk/emit! store (de/request-simple-export {:export (assoc export :file-id (uuid/next))}))
       (await (async/wait-for #(true? @downloaded) "the export runs"))
       (await (async/settle))
       (t/is (nil? (:notification @store)))))))
