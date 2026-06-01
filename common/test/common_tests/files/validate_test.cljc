;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.validate-test
  "Exhaustive tests for the change-scoped partial validation functions in
   app.common.files.validate:

     - validate-file-affected   – returns nil or list of errors
     - validate-file-affected!  – same but raises on non-empty errors

   The tests verify the scoping logic implemented by extract-affected-ids
   (tested indirectly) by injecting controlled broken states into specific
   pages / components and confirming that only the expected entities are
   validated."
  (:require
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ----------------------------------------------------------------
;; Test-local helpers
;; ----------------------------------------------------------------

(defn- inject-broken-child
  "Add a reference to a non-existent shape ID to the root shape's
  children list on the page identified by `page-label`.

  This causes `check-parent-children` to report a :child-not-found
  error whenever that page is validated."
  [file page-label]
  (let [page-id    (thi/id page-label)
        missing-id (uuid/next)]
    (ctf/update-file-data
     file
     (fn [file-data]
       (ctpl/update-page
        file-data
        page-id
        (fn [page]
          (let [root (ctst/get-shape page uuid/zero)]
            (ctst/set-shape page (update root :shapes conj missing-id)))))))))

(defn- inject-broken-component
  "Add a deleted component with `:objects nil` to the file.

  This causes `check-component` to report a
  :component-nil-objects-not-allowed error whenever that component is
  validated.  The component id is registered under `comp-label` in the
  ids-map so callers can look it up with `(thi/id comp-label)`."
  [file comp-label]
  (let [comp-id (thi/new-id! comp-label)]
    (ctf/update-file-data
     file
     (fn [file-data]
       (assoc-in file-data [:components comp-id]
                 {:id               comp-id
                  :name             "broken-component"
                  :objects          nil
                  :deleted          true
                  :main-instance-id (uuid/next)
                  :main-instance-page (uuid/next)})))))

;; ----------------------------------------------------------------
;; 1. Feature gate
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-no-feature
  (t/testing "returns nil when file does not have the components/v2 feature"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (assoc :features #{}))
          page-id (thi/id :page1)
          changes [{:type :add-obj :page-id page-id :id (uuid/next)}]]
      (t/is (nil? (cfv/validate-file-affected file {} changes))))))

;; ----------------------------------------------------------------
;; 2. Empty changes – nothing to validate
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-empty-changes
  (t/testing "returns nil when the changes list is empty"
    (let [file (thf/sample-file :file1 :page-label :page1)]
      (t/is (nil? (cfv/validate-file-affected file {} []))))))

;; ----------------------------------------------------------------
;; 3. Page-level scoping – add-obj / mod-obj / fix-obj key: :page-id
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-page-scoping-misses-untouched-page
  (t/testing "add-obj on page1 does not validate broken page2"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          ;; The change only touches page1
          changes  [{:type :add-obj :page-id page1-id :id (uuid/next)}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "mod-obj on page1 does not validate broken page2"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          changes  [{:type :mod-obj :page-id page1-id :id uuid/zero
                     :operations [{:type :set :attr :name :val "root"}]}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "fix-obj on page1 does not validate broken page2"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          changes  [{:type :fix-obj :page-id page1-id :id (uuid/next)
                     :operations []}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes))))))

(t/deftest validate-file-affected-page-scoping-catches-error-on-touched-page
  (t/testing "add-obj on broken page1 surfaces the error"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :add-obj :page-id page1-id :id (uuid/next)}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "mod-obj on broken page1 surfaces the error"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :mod-obj :page-id page1-id :id uuid/zero
                     :operations [{:type :set :attr :name :val "root"}]}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "reg-objects on broken page1 surfaces the error"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :reg-objects :page-id page1-id :shapes []}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "mov-objects on broken page1 surfaces the error"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :mov-objects :page-id page1-id :parent-id uuid/zero :shapes []}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors))))))

;; ----------------------------------------------------------------
;; 4. add-page / mod-page – scoped by :id (not :page-id)
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-add-page-scoped-by-id
  (t/testing "add-page with :id=page2 validates page2 (broken → errors)"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page2-id (thi/id :page2)
          changes  [{:type :add-page :id page2-id}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "add-page with :id=page1 does not validate broken page2"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          changes  [{:type :add-page :id page1-id}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "mod-page with :id=page2 validates page2 (broken → errors)"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page2-id (thi/id :page2)
          changes  [{:type :mod-page :id page2-id}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors))))))

;; ----------------------------------------------------------------
;; 5. del-obj – shape-level op scoped by :page-id
;;    del-page / del-component / mov-page / purge-component – no-ops
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-del-obj-scopes-page
  (t/testing "del-obj scopes its :page-id just like add-obj / mod-obj"
    ;; del-obj is in the same shape-level ops bucket and scopes by :page-id.
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :del-obj :page-id page1-id :id (uuid/next)}]]
      ;; page1 has an error and del-obj touches page1 → error surfaced
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "del-obj on page1 does not validate broken page2"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          changes  [{:type :del-obj :page-id page1-id :id (uuid/next)}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes))))))

(t/deftest validate-file-affected-noop-change-types
  (t/testing "del-page produces no affected entries"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :del-page :id page1-id}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "del-component produces no affected entries"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          comp-id (thi/id :comp1)
          changes [{:type :del-component :id comp-id}]]
      (t/is (nil? (cfv/validate-file-affected file {} changes)))))

  (t/testing "mov-page produces no affected entries"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :mov-page :id page1-id}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "purge-component produces no affected entries"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          comp-id (thi/id :comp1)
          changes [{:type :purge-component :id comp-id}]]
      (t/is (nil? (cfv/validate-file-affected file {} changes)))))

  (t/testing "add-color (library change) produces no affected entries"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          changes  [{:type :add-color :id (uuid/next) :color {}}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "mod-color (library change) produces no affected entries"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          changes  [{:type :mod-color :id (uuid/next) :color {}}]]
      (t/is (nil? (cfv/validate-file-affected file' {} changes))))))

;; ----------------------------------------------------------------
;; 6. add-component / mod-component – scoped by :id (component-id)
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-add-component-scoped-by-id
  (t/testing "add-component :id=comp1 validates comp1 (broken → errors)"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          comp-id (thi/id :comp1)
          changes [{:type :add-component :id comp-id}]]
      (let [errors (cfv/validate-file-affected file {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :component-nil-objects-not-allowed (:code %)) errors)))))

  (t/testing "add-component :id=other-id does not validate broken comp1"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          _       (thi/id :comp1)
          changes [{:type :add-component :id (uuid/next)}]]
      (t/is (nil? (cfv/validate-file-affected file {} changes)))))

  (t/testing "mod-component :id=comp1 validates comp1 (broken → errors)"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          comp-id (thi/id :comp1)
          changes [{:type :mod-component :id comp-id}]]
      (let [errors (cfv/validate-file-affected file {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :component-nil-objects-not-allowed (:code %)) errors))))))

;; ----------------------------------------------------------------
;; 7. restore-component – scopes BOTH :id (component) and :page-id
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-restore-component-scopes-page
  (t/testing "restore-component touches its :page-id (broken page → errors)"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :restore-component :id (uuid/next) :page-id page1-id}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "restore-component does not validate a page it does not reference"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          changes  [{:type :restore-component :id (uuid/next) :page-id page1-id}]]
      ;; page2 has an error but the change only touches page1
      (t/is (nil? (cfv/validate-file-affected file' {} changes))))))

(t/deftest validate-file-affected-restore-component-scopes-component
  (t/testing "restore-component touches its component :id (broken component → errors)"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          comp-id (thi/id :comp1)
          page1-id (thi/id :page1)
          changes [{:type :restore-component :id comp-id :page-id page1-id}]]
      (let [errors (cfv/validate-file-affected file {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :component-nil-objects-not-allowed (:code %)) errors))))))

;; ----------------------------------------------------------------
;; 8. Mixed changes – union of affected entities
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-mixed-changes-union
  (t/testing "two changes on different pages: both pages are validated"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          ;; page2 is broken; page1 is clean
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          page2-id (thi/id :page2)
          ;; Both pages are touched
          changes  [{:type :add-obj :page-id page1-id :id (uuid/next)}
                    {:type :mod-obj :page-id page2-id :id uuid/zero
                     :operations []}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        ;; page2 is validated → error surfaced
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors)))))

  (t/testing "del-page (true no-op) mixed with add-obj on page1: only page1 is validated"
    (let [file     (-> (thf/sample-file :file1 :page-label :page1)
                       (thf/add-sample-page :page2))
          ;; page2 is broken; page1 is clean
          file'    (inject-broken-child file :page2)
          page1-id (thi/id :page1)
          page2-id (thi/id :page2)
          ;; del-page on page2 is a no-op; add-obj on page1 scopes page1 only
          changes  [{:type :del-page :id page2-id}
                    {:type :add-obj :page-id page1-id :id (uuid/next)}]]
      ;; page2's error is NOT surfaced because del-page produces no scope
      (t/is (nil? (cfv/validate-file-affected file' {} changes)))))

  (t/testing "duplicate page-ids in changes are deduplicated (page validated once)"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          ;; Three changes all touching the same page
          changes  [{:type :add-obj   :page-id page1-id :id (uuid/next)}
                    {:type :mod-obj   :page-id page1-id :id uuid/zero :operations []}
                    {:type :del-obj   :page-id page1-id :id (uuid/next)}]]
      ;; del-obj excluded, add-obj + mod-obj both scope page1; error surfaced once
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        ;; There should be exactly one :child-not-found (not duplicated)
        (t/is (= 1 (count (filter #(= :child-not-found (:code %)) errors))))))))

;; ----------------------------------------------------------------
;; 9. reorder-children and component-context changes
;; ----------------------------------------------------------------

(t/deftest validate-file-affected-reorder-children
  (t/testing "reorder-children on broken page surfaces the error"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :reorder-children :page-id page1-id :id uuid/zero :shapes []}]]
      (let [errors (cfv/validate-file-affected file' {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :child-not-found (:code %)) errors))))))

(t/deftest validate-file-affected-obj-in-component
  (t/testing "add-obj with :component-id (not :page-id) scopes a component"
    ;; When a shape change carries :component-id instead of :page-id it
    ;; means the change happened inside a deleted component's object tree.
    ;; extract-affected-ids routes it to :component-ids.
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          comp-id (thi/id :comp1)
          changes [{:type :add-obj :component-id comp-id :id (uuid/next)}]]
      (let [errors (cfv/validate-file-affected file {} changes)]
        (t/is (seq errors))
        (t/is (some #(= :component-nil-objects-not-allowed (:code %)) errors)))))

  (t/testing "add-obj with :component-id does not scope an unrelated component"
    (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                      (inject-broken-component :comp1))
          _       (thi/id :comp1)
          changes [{:type :add-obj :component-id (uuid/next) :id (uuid/next)}]]
      (t/is (nil? (cfv/validate-file-affected file {} changes))))))

;; ----------------------------------------------------------------
;; 10. validate-file-affected! – raises vs returns nil
;; ----------------------------------------------------------------

(t/deftest validate-file-affected!-returns-nil-on-clean-file
  (t/testing "returns nil when the file has no errors in the touched page"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          page1-id (thi/id :page1)
          changes  [{:type :add-obj :page-id page1-id :id (uuid/next)}]]
      (t/is (nil? (cfv/validate-file-affected! file {} changes))))))

(t/deftest validate-file-affected!-returns-nil-empty-changes
  (t/testing "returns nil when there are no changes (no pages validated)"
    (let [file (thf/sample-file :file1 :page-label :page1)]
      (t/is (nil? (cfv/validate-file-affected! file {} []))))))

(t/deftest validate-file-affected!-raises-on-error
  (t/testing "raises an exception when the touched page has validation errors"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :mod-obj :page-id page1-id :id uuid/zero
                     :operations []}]]
      (t/is (thrown? #?(:clj Exception :cljs js/Error)
                     (cfv/validate-file-affected! file' {} changes)))))

  (t/testing "raised exception is of :validation type with :referential-integrity code"
    (let [file     (thf/sample-file :file1 :page-label :page1)
          file'    (inject-broken-child file :page1)
          page1-id (thi/id :page1)
          changes  [{:type :mod-obj :page-id page1-id :id uuid/zero
                     :operations []}]]
      (try
        (cfv/validate-file-affected! file' {} changes)
        (t/is false "expected exception to be thrown")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
          (let [data (ex-data e)]
            (t/is (= :validation (:type data)))
            (t/is (= :referential-integrity (:code data)))
            (t/is (seq (:details data)))))))))
