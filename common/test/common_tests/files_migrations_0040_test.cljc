;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0040-test
  (:require
   [app.common.data :as d]
   [app.common.files.migrations :as cfm]
   [app.common.files.validate :as cfv]
   [app.common.geom.shapes :as gsh]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ======================================================================
;; 0040-repair-collapsed-layout-children
;; Layout children saved at the 0.01 px minimum (issue 13353) get back the
;; sizing of their main, and the layout gives them their size.
;; ======================================================================

(def ^:private migration-id
  "0040-repair-collapsed-layout-children")

(def ^:private flex-defaults
  {:layout :flex
   :layout-flex-dir :row
   :layout-padding-type :simple
   :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
   :layout-gap-type :multiple
   :layout-gap {:row-gap 0 :column-gap 0}
   :layout-align-items :start
   :layout-align-content :stretch
   :layout-justify-items :start
   :layout-justify-content :start
   :layout-wrap-type :nowrap
   :width 100
   :height 50})

(defn- raw-update
  [file label f]
  (let [page (thf/current-page file)]
    (update file :data
            (fn [fdata]
              (ctpl/update-page fdata (:id page)
                                #(ctst/set-shape % (f (ctst/get-shape % (thi/id label)))))))))

(defn- resize
  [shape attr value]
  (gsh/transform-shape shape (ctm/change-dimensions-modifiers shape attr value {:ignore-lock? true})))

(defn- add-main
  "A component whose main is a row (67 px, padding 8, gap 4) with a 16 px icon
  and a fill board (padding 2) that holds a fill text."
  [file]
  (-> file
      (tho/add-frame :main-row (merge flex-defaults {:width 67
                                                     :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}
                                                     :layout-gap {:row-gap 0 :column-gap 4}}))
      (tho/add-frame :main-board (merge flex-defaults {:parent-label :main-row
                                                       :layout-flex-dir :column
                                                       :layout-item-h-sizing :fill
                                                       :layout-padding {:p1 0 :p2 2 :p3 0 :p4 2}}))
      (tho/add-text :main-text "Input"
                    :text-params {:parent-label :main-board :grow-type :fixed :width 50 :height 16
                                  :layout-item-h-sizing :fill})
      (tho/add-rect :main-icon :parent-label :main-row :width 16 :height 16)
      (thc/make-component :component1 :main-row)))

(defn- add-copy
  "A copy of the component of `library` with the board and the text stored
  collapsed, as the issue shows."
  [file library]
  (let [touched #{:geometry-group :layout-item-h-sizing}
        file    (thc/instantiate-component file :component1 :copy-row
                                           :library library
                                           :children-labels [:c1 :c2 :c3])
        copy-of (fn [file main-label copy-label]
                  (let [copy (d/seek #(= (thi/id main-label) (:shape-ref %))
                                     (vals (:objects (thf/current-page file))))]
                    (thi/set-id! copy-label (:id copy))
                    file))]
    (-> file
        (copy-of :main-board :copy-board)
        (copy-of :main-text :copy-text)
        (raw-update :copy-row #(assoc % :layout-item-h-sizing :fix))
        (raw-update :copy-board #(-> (resize % :width 1)
                                     (resize :width 0.01)
                                     (assoc :layout-item-h-sizing :fix :touched touched)))
        (raw-update :copy-text #(-> (resize % :width 3.99)
                                    (assoc :layout-item-h-sizing :fix
                                           :touched (into touched [:content-group :text-content-attribute :text-content-text])))))))

(defn- migrate
  "Runs the migration on the data of `file`; `libs` are the libraries of its file."
  [file libs]
  (cfm/migrate-data (assoc (ctf/file-data file) :id (:id file) :libs (delay libs)) migration-id))

(defn- migrated-file
  [file libs]
  (let [data (dissoc (migrate file libs) :libs)]
    (assoc file :data data)))

(defn- shape
  [file label]
  (get (:objects (thf/current-page file)) (thi/id label)))

(t/deftest migration-0040-repairs-the-copy-of-a-main-in-the-same-file
  (let [file  (-> (thf/sample-file :file1)
                  (add-main)
                  (add-copy nil))
        file' (migrated-file file {(:id file) file})
        board (shape file' :copy-board)
        text  (shape file' :copy-text)]
    (t/is (<= (:width (shape file :copy-board)) 0.011))
    (t/is (= [:fill 31.0] [(:layout-item-h-sizing board) (double (:width board))]))
    (t/is (= [:fill 27.0] [(:layout-item-h-sizing text) (double (:width text))]))
    (t/is (= #{:geometry-group} (:touched board)))
    (t/is (some? (cfv/validate-file-schema! file')))))

(t/deftest migration-0040-repairs-the-copy-of-a-main-in-a-library
  (let [library (-> (thf/sample-file :lib :page-label :lib-page)
                    (add-main))
        file    (-> (thf/sample-file :file1)
                    (add-copy library))
        libs    {(:id library) library}
        file'   (migrated-file file libs)
        board   (shape file' :copy-board)
        text    (shape file' :copy-text)]
    (t/is (= [:fill 31.0] [(:layout-item-h-sizing board) (double (:width board))]))
    (t/is (= [:fill 27.0] [(:layout-item-h-sizing text) (double (:width text))]))
    (t/is (some? (cfv/validate-file-schema! file')))))

(t/deftest migration-0040-leaves-library-copies-without-the-libraries
  (let [library (-> (thf/sample-file :lib :page-label :lib-page)
                    (add-main))
        file    (-> (thf/sample-file :file1)
                    (add-copy library))
        data    (ctf/file-data file)
        data'   (dissoc (migrate file nil) :libs)]
    (t/is (= (dissoc data :libs) data'))
    (t/is (identical? (get-in data [:pages-index (:id (thf/current-page file)) :objects])
                      (get-in data' [:pages-index (:id (thf/current-page file)) :objects])))))

(t/deftest migration-0040-keeps-a-healthy-file-as-it-is
  (let [file  (-> (thf/sample-file :file1)
                  (add-main))
        data  (ctf/file-data file)
        data' (migrate file {(:id file) file})
        page  #(get-in % [:pages-index (:id (thf/current-page file))])]
    (t/is (= (dissoc data' :libs) data))
    (t/is (identical? (:objects (page data)) (:objects (page data'))))))

(t/deftest migration-0040-repairs-once
  (let [file   (-> (thf/sample-file :file1)
                   (add-main)
                   (add-copy nil))
        file'  (migrated-file file {(:id file) file})
        data'  (ctf/file-data file')
        data'' (migrate file' {(:id file') file'})
        page   #(get-in % [:pages-index (:id (thf/current-page file))])]
    (t/is (identical? (:objects (page data')) (:objects (page data''))))))
