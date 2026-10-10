;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-variants-test
  "The checks the workspace runs before combining components as variants
   and before a variant switch, and the branches of the variant events that
   the composable cases (`frontend-tests.composable-tests.comp.sync-test`)
   do not reach: a combine on another page, a switch that would nest a
   component in itself, the shortcuts with no variant selected and the
   rename of a plain component."
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as cthv]
   [app.common.types.components-list :as ctkl]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- setup-file
  "A variant set (members :m01 and :m02, each with a child), a copy of :m01,
   two plain components and a copy of the first."
  []
  (-> (cthf/sample-file :file1)
      (cthv/add-variant-with-child :v01 :c01 :m01 :c02 :m02 :m01-child :m02-child)
      (cthc/instantiate-component :c01 :copy01 :children-labels [:copy01-child])
      (ctho/add-simple-component :plain1 :plain1-root :plain1-child)
      (ctho/add-simple-component :plain2 :plain2-root :plain2-child)
      (cthc/instantiate-component :plain1 :plain1-copy)))

(defn- state-of
  [file]
  @(ths/setup-store file))

(t/deftest valid-components-for-variants
  (let [file    (setup-file)
        state   (state-of file)
        page-id (:current-page-id state)
        valid?  #(dwv/valid-components-for-variants? state page-id (mapv cthi/id %))]
    (t/testing "two plain main roots"
      (t/is (valid? [:plain1-root :plain2-root])))
    (t/testing "fewer than two distinct components"
      (t/is (not (valid? [:plain1-root])))
      (t/is (not (valid? [:plain1-root :plain1-root]))))
    (t/testing "a variant main"
      (t/is (not (valid? [:plain1-root :m01]))))
    (t/testing "a copy"
      (t/is (not (valid? [:plain1-root :plain1-copy]))))
    (t/testing "a page that is not the current one"
      (t/is (not (dwv/valid-components-for-variants?
                  state (uuid/next) [(cthi/id :plain1-root) (cthi/id :plain2-root)]))))))

(t/deftest valid-variant-switch
  (let [file   (setup-file)
        state  (state-of file)
        valid? (fn [label pos val]
                 (dwv/valid-variant-switch? state (cths/get-shape file label) pos val))]
    (t/testing "a variant copy head and a value some member holds"
      (t/is (valid? :copy01 0 "Value2"))
      (t/is (valid? :copy01 0 "Value1")))
    (t/testing "a value no member holds"
      (t/is (not (valid? :copy01 0 "Value3"))))
    (t/testing "a position outside the properties"
      (t/is (not (valid? :copy01 1 "Value2")))
      (t/is (not (valid? :copy01 -1 "Value2"))))
    (t/testing "a value that is not a string"
      (t/is (not (valid? :copy01 0 2))))
    (t/testing "not a variant copy head"
      (t/is (not (valid? :m01 0 "Value2")))
      (t/is (not (valid? :copy01-child 0 "Value2")))
      (t/is (not (valid? :plain1-copy 0 "Value2"))))))

(defn- all-transactions-committed
  "A `run-store` stopper: the first undo commit after which no transaction
   is pending, for events that commit their transaction asynchronously."
  [store]
  (fn [stream]
    (->> stream
         (rx/filter (ptk/type? ::dwu/commit-undo-transaction))
         (rx/filter #(empty? (get-in @store [:workspace-undo :transactions-pending]))))))

(defn- go-to-page
  "Stands in for `dcm/go-to-workspace`, which needs the router: makes
   `page-id` the current page and announces it as initialized."
  [& {:keys [page-id]}]
  (ptk/reify ::go-to-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-page-id page-id))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event :app.main.data.workspace.pages/initialize-page {})))))

(t/deftest combine-on-another-page
  (t/async
    done
    (mock/with-mocks
      {dcm/go-to-workspace go-to-page}
      (fn [inner-done]
        (let [file    (cthf/sample-file :file1)
              page-1  (cthf/current-page-id file)
              file    (-> file
                          (cthf/add-sample-page :page2)
                          (ctho/add-simple-component :plain1 :plain1-root :plain1-child)
                          (ctho/add-simple-component :plain2 :plain2-root :plain2-child)
                          (vary-meta assoc :current-page-id page-1))
              store   (ths/setup-store file)
              ids     [(cthi/id :plain1-root) (cthi/id :plain2-root)]]
          (ths/run-store
           store inner-done
           [(dwv/combine-as-variants ids {:page-id (cthi/id :page2)
                                          :trigger "plugin:combine-as-variants"})]
           (fn [state]
             (let [data (:data (ths/get-file-from-state state))
                   c1   (ctkl/get-component data (cthi/id :plain1))
                   c2   (ctkl/get-component data (cthi/id :plain2))]
               (t/is (= (cthi/id :page2) (:current-page-id state)))
               (t/is (some? (:variant-id c1)))
               (t/is (= (:variant-id c1) (:variant-id c2)))))
           (all-transactions-committed store))))
      done)))

(t/deftest switch-into-a-nesting-loop
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (cthv/add-variant :v01 :c01 :m01 :c02 :m02)
                     (cthc/instantiate-component :c01 :nested :parent-label :m02))
          store  (ths/setup-store file)
          errors (atom 0)
          params (with-meta {:shapes [(cths/get-shape file :nested)] :pos 0 :val "Value2"}
                   {:on-error #(swap! errors inc)})]
      (ths/run-store
       store done
       [(dwv/variants-switch params)]
       (fn [state]
         (t/is (= 1 @errors))
         (t/is (= (cthi/id :c01)
                  (:component-id (cths/get-shape (ths/get-file-from-state state) :nested)))))))))

(defn- object-count
  [file]
  (count (:objects (cthf/current-page file))))

(defn- run-selected
  "Run `event` on a store for `file` with the shapes `labels` selected, then
   `check` the resulting file."
  [done file labels event check]
  (ths/run-store
   (ths/setup-store file) done
   [(dws/select-shapes (into (d/ordered-set) (map cthi/id) labels)) event]
   #(check (ths/get-file-from-state %))))

(defn- file-with-a-rect
  []
  (-> (cthf/sample-file :file1)
      (cths/add-sample-shape :rect)))

(t/deftest duplicate-shortcut-with-nothing-selected
  (t/async
    done
    (let [file (file-with-a-rect)]
      (run-selected done file [] (dwv/duplicate-or-add-variant)
                    #(t/is (= (object-count file) (object-count %)))))))

(t/deftest duplicate-shortcut-on-a-plain-shape
  (t/async
    done
    (let [file (file-with-a-rect)]
      (run-selected done file [:rect] (dwv/duplicate-or-add-variant)
                    #(t/is (= (inc (object-count file)) (object-count %)))))))

(t/deftest create-component-shortcut-on-a-plain-shape
  (t/async
    done
    (run-selected done (file-with-a-rect) [:rect] (dwv/add-component-or-variant)
                  #(t/is (= 1 (count (ctkl/components-seq (:data %))))))))

(t/deftest rename-of-a-plain-component
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (ctho/add-simple-component :plain1 :plain1-root :plain1-child))]
      (ths/run-store
       (ths/setup-store file) done
       [(dwv/rename-comp-or-variant-and-main (cthi/id :plain1) "Renamed")]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= "Renamed" (:name (ctkl/get-component (:data file') (cthi/id :plain1)))))
           (t/is (= "Renamed" (:name (cths/get-shape file' :plain1-root))))))))))
