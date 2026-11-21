;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-sync-text-wasm-test
  "Regression test for #12721: after component sync with render-wasm/v1,
  auto-grow text copies must be scheduled for a WASM re-measure with
  :skip-component-sync?, so the re-measure commit does not start another
  sync."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(def ^:private orig-resize-wasm-text-all dwwt/resize-wasm-text-all)

(def ^:private orig-clear-thumbnail dwt/clear-thumbnail)

(def ^:private resize-calls (atom []))

(defn- mock-clear-thumbnail
  ([_ _] nil)
  ([_ _ _ _] nil))

(defn- mock-resize-wasm-text-all
  ([ids] (mock-resize-wasm-text-all ids nil))
  ([ids opts]
   (swap! resize-calls conj {:ids ids :opts opts})
   nil))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (reset! resize-calls [])
             (set! dwwt/resize-wasm-text-all mock-resize-wasm-text-all)
             ;; sync-file clears frame thumbnails on a 200ms timer that
             ;; outlives the test and calls rp/cmd! inside later tests.
             (set! dwt/clear-thumbnail mock-clear-thumbnail))
   :after  (fn []
             (set! dwwt/resize-wasm-text-all orig-resize-wasm-text-all)
             (set! dwt/clear-thumbnail orig-clear-thumbnail))})

(defn- setup-file
  [& {:keys [grow-type] :or {grow-type :auto-width}}]
  (-> (cthf/sample-file :file1)
      (ctho/add-frame-with-text :main-root :main-child "hello world"
                                :child-params {:grow-type grow-type})
      (cthc/make-component :component1 :main-root)
      (cthc/instantiate-component :component1 :copy-root
                                  {:children-labels [:copy-child]})))

(defn- update-main-text
  [file update-fn]
  (let [page       (cthf/current-page file)
        main-child (cths/get-shape file :main-child)
        changes    (cls/generate-update-shapes
                    (pcb/empty-changes nil (:id page))
                    #{(:id main-child)}
                    update-fn
                    (:objects page)
                    {})]
    (cthf/apply-changes file changes)))

(defn- bump-main-font-size
  [file]
  (update-main-text file #(assoc-in % [:content :children 0 :children 0 :children 0 :font-size] "32")))

(defn- recolor-main-text
  [file]
  (update-main-text file #(assoc-in % [:content :children 0 :children 0 :children 0 :fills]
                                    [{:fill-color "#ff0000" :fill-opacity 1}])))

(defn- fade-main-text
  [file]
  (update-main-text file #(assoc % :opacity 0.5)))

(defn- widen-main-text
  [file]
  (update-main-text file #(gsh/transform-shape % (ctm/change-dimensions-modifiers % :width 300))))

(defn- collect-layout-updates
  "Subscribe before emit; accumulates deref'd `:layout/update` payloads."
  [store]
  (let [acc (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter (ptk/type? :layout/update))
         (rx/tap #(swap! acc conj (deref %)))
         (rx/subs! (fn [_] nil)))
    acc))

(t/deftest test-sync-file-schedules-wasm-text-remeasure
  (t/async
    done
    (let [file       (-> (setup-file) bump-main-font-size)
          store      (ths/setup-store file)
          page-id    (cthf/current-page-id file)
          copy-id    (:id (cths/get-shape file :copy-child))
          undo-group (uuid/next)
          layout-updates (collect-layout-updates store)

          events
          [(dwl/sync-file (:id file) (:id file)
                          :components (cthi/id :component1)
                          undo-group)]]

      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (= 1 (count @resize-calls))
               "resize-wasm-text-all should run once after sync")
         (let [{:keys [ids opts]} (first @resize-calls)
               layout-ids (->> @layout-updates
                               (filter #(= page-id (:page-id %)))
                               (mapcat :ids)
                               set)]
           (t/is (some #{copy-id} ids)
                 "re-measure ids should include the copy text")
           (t/is (:skip-component-sync? opts)
                 "re-measure must use :skip-component-sync? so its commit does not start another sync")
           (t/is (= undo-group (:undo-group opts)))
           (t/is (every? layout-ids ids)
                 (str "current-page :layout/update ids must include re-measure ids; "
                      "layout=" layout-ids " re-measure=" ids))))))))

(defn- collect-commits
  "Subscribe before emit; accumulates the commits that touch `id`."
  [store id]
  (let [acc (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter dch/commit?)
         (rx/map deref)
         (rx/filter #(some (comp #{id} :id) (:redo-changes %)))
         (rx/subs! #(swap! acc conj %)))
    acc))

(t/deftest test-sync-file-without-undo-group-shares-one-with-its-remeasure
  (t/async
    done
    (let [file    (-> (setup-file) bump-main-font-size)
          store   (ths/setup-store file)
          copy-id (:id (cths/get-shape file :copy-child))
          commits (collect-commits store copy-id)
          ;; The library update dialog and the color and typography syncs
          ;; pass no undo group
          events  [(dwl/sync-file (:id file) (:id file)
                                  :components (cthi/id :component1))]]
      (ths/run-store
       store done events
       (fn [_]
         (let [sync-group     (:undo-group (first @commits))
               remeasure-opts (:opts (first @resize-calls))]
           (t/is (some? sync-group)
                 "the sync commit gets an undo group")
           (t/is (= sync-group (:undo-group remeasure-opts))
                 "the re-measure shares it, so one undo reverts both")))))))

(defn- run-sync-in-undo-transaction
  "Runs, inside one undo transaction, an edit commit with `edit-group` (nil
  for none) and then `sync-file` with no undo group, and checks that the
  sync commit and its re-measure take the edit's group. With `edit-group`
  the edit's history entry is appended by hand, as the workspace undo
  watcher does, so the transaction gets that group as its `:undo-group`."
  [done edit-group]
  (let [file    (-> (setup-file) bump-main-font-size)
        store   (ths/setup-store file)
        page-id (cthf/current-page-id file)
        tx      (js/Symbol)
        redo    [{:type :mod-page :id page-id :name "Edited"}]
        undo    [{:type :mod-page :id page-id :name "Page 1"}]
        groups  (atom [])
        sub     (->> (ptk/input-stream store)
                     (rx/filter dch/commit?)
                     (rx/subs! #(swap! groups conj (:undo-group (deref %)))))
        events  (cond-> [(dwu/start-undo-transaction tx :timeout 0)
                         ;; Stands for the color, typography or delete commit
                         (dch/commit-changes {:redo-changes redo
                                              :undo-changes undo
                                              :undo-group edit-group})]
                  (some? edit-group)
                  (conj (dwu/append-undo {:undo-changes undo
                                          :redo-changes redo
                                          :undo-group edit-group
                                          :tags #{}
                                          :translation false}
                                         false))

                  :always
                  (conj (dwl/sync-file (:id file) (:id file)
                                       :components (cthi/id :component1))
                        (dwu/commit-undo-transaction tx)))]
    (ths/run-store
     store done events
     (fn [_]
       (rx/dispose! sub)
       (let [first-group    (first @groups)
             remeasure-opts (:opts (first @resize-calls))]
         (t/is (some? first-group))
         (when (some? edit-group)
           (t/is (= edit-group first-group)))
         (t/is (< 1 (count @groups))
               "the sync commits after the edit")
         (t/is (every? #{first-group} @groups)
               "the sync commit takes the transaction's group")
         (t/is (= first-group (:undo-group remeasure-opts))
               "the re-measure takes the transaction's group"))))))

;; Scenario: the color and typography edits commit and call `sync-file`
;; with no undo group inside their own undo transaction. Proves: the sync
;; commit and its re-measure take the transaction's group, so one undo
;; reverts the edit and its sync, and a rejected sync rolls back both.
(t/deftest test-sync-file-in-undo-transaction-shares-its-group
  (t/async
    done
    (run-sync-in-undo-transaction done nil)))

;; Scenario: `delete-component` commits with its own undo group inside the
;; sidebar's undo transaction, then syncs with no group. Proves: the sync
;; takes the transaction's `:undo-group` (the delete's), not the group the
;; transaction was opened with.
(t/deftest test-sync-file-in-undo-transaction-takes-the-entry-group
  (t/async
    done
    (run-sync-in-undo-transaction done (uuid/next))))

(t/deftest test-sync-file-skips-remeasure-for-fixed-text
  (t/async
    done
    (let [file   (-> (setup-file :grow-type :fixed) bump-main-font-size)
          store  (ths/setup-store file)

          events
          [(dwl/sync-file (:id file) (:id file)
                          :components (cthi/id :component1))]]

      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (empty? @resize-calls)
               "fixed-grow texts must not be scheduled for a WASM re-measure"))))))

(defn- set-main-grow-type
  [grow-type]
  (fn [file]
    (update-main-text file #(assoc % :grow-type grow-type))))

(t/deftest test-sync-file-remeasures-text-that-becomes-auto-grow
  (t/async
    done
    (let [file    (-> (setup-file :grow-type :fixed)
                      ((set-main-grow-type :auto-height)))
          store   (ths/setup-store file)
          copy-id (:id (cths/get-shape file :copy-child))
          events  [(dwl/sync-file (:id file) (:id file)
                                  :components (cthi/id :component1))]]
      (ths/run-store
       store done events
       (fn [_]
         (t/is (= 1 (count @resize-calls))
               "a copy turned auto-grow by the sync is re-measured")
         (t/is (some #{copy-id} (:ids (first @resize-calls)))
               "the re-measure includes the copy"))))))

(t/deftest test-sync-file-skips-text-that-becomes-fixed
  (t/async
    done
    (let [file           (-> (setup-file :grow-type :auto-height)
                             ((set-main-grow-type :fixed)))
          store          (ths/setup-store file)
          page-id        (cthf/current-page-id file)
          copy-id        (:id (cths/get-shape file :copy-child))
          layout-updates (collect-layout-updates store)
          events         [(dwl/sync-file (:id file) (:id file)
                                         :components (cthi/id :component1))]]
      (ths/run-store
       store done events
       (fn [_]
         (t/is (empty? @resize-calls)
               "a copy turned fixed by the sync is not re-measured")
         (t/is (not-any? #(and (= page-id (:page-id %))
                               (some #{copy-id} (:ids %)))
                         @layout-updates)
               "and gets no layout update for a re-measure"))))))

(defn- sync-remeasures
  "Sync the copy after `change-main` and call `check` with the re-measure
  requests."
  [done change-main check]
  (let [file   (change-main (setup-file :grow-type :auto-height))
        store  (ths/setup-store file)
        events [(dwl/sync-file (:id file) (:id file)
                               :components (cthi/id :component1))]]
    (ths/run-store store done events (fn [_] (check @resize-calls)))))

(t/deftest test-sync-file-skips-remeasure-for-fill-change
  (t/async
    done
    (sync-remeasures done recolor-main-text
                     (fn [calls]
                       (t/is (empty? calls)
                             "a text color change does not change its size")))))

(t/deftest test-sync-file-skips-remeasure-for-opacity-change
  (t/async
    done
    (sync-remeasures done fade-main-text
                     (fn [calls]
                       (t/is (empty? calls)
                             "an opacity change does not change the text size")))))

(t/deftest test-sync-file-remeasures-auto-height-text-on-font-change
  (t/async
    done
    (sync-remeasures done bump-main-font-size
                     (fn [calls]
                       (t/is (= 1 (count calls))
                             "a font change can change the text size")))))

(t/deftest test-sync-file-remeasures-auto-height-text-on-width-change
  (t/async
    done
    (sync-remeasures done widen-main-text
                     (fn [calls]
                       (t/is (= 1 (count calls))
                             "a wider auto-height text can wrap into fewer lines")))))
