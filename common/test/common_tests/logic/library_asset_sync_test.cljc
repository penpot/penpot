;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.library-asset-sync-test
  "Library color and typography sync in component copies. The sync writes
   the asset's new value into every shape that uses it, copies included.
   That write is derived from the library, not authored on the copy, so it
   must not touch the copy (`mem:common/component-sync-contract`), and later
   main edits must still reach it."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.text :as txt]
   [app.common.types.typographies-list :as cttl]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- sync-file
  "Apply the sync of `asset-type` (`:colors`, `:typographies` or
   `:components`) for `asset-id` from the file to itself."
  [file asset-type asset-id]
  (let [changes (cll/generate-sync-file-changes (pcb/empty-changes)
                                                nil
                                                asset-type
                                                (:id file)
                                                asset-id
                                                (:id file)
                                                {(:id file) file}
                                                (:id file))]
    (thf/apply-changes file changes)))

(defn- update-shape
  "Change the shape `label` with `f` through the production change path."
  [file label f]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            f
                                            (:objects page)
                                            {})]
    (thf/apply-changes file changes)))

;; ---- Colors

(defn- color-fill
  [color]
  {:fill-color          color
   :fill-opacity        1
   :fill-color-ref-id   (thi/id :color1)
   :fill-color-ref-file (thi/id :file1)})

(defn- component-using-color
  "A component whose child is filled with the library color :color1
   (#111111), and a copy of it."
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-library-color :color1 :color "#111111")
      (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                          :main-child-params {:fills [(color-fill "#111111")]}
                                          :copy-root-params {:children-labels [:copy-child]})))

(defn- change-library-color
  [file color]
  (-> file
      (assoc-in [:data :colors (thi/id :color1) :color] color)
      (sync-file :colors (thi/id :color1))))

(t/deftest library-color-sync-reaches-the-copy-untouched
  (let [file' (change-library-color (component-using-color) "#222222")
        copy  (ths/get-shape file' :copy-child)]
    (t/is (= "#222222" (-> copy :fills first :fill-color)))
    ;; a library sync is a derived write
    (t/is (nil? (:touched copy)))))

(t/deftest main-fill-edit-reaches-the-copy-after-a-library-color-sync
  (let [file' (-> (component-using-color)
                  (change-library-color "#222222")
                  (sync-file :components (thi/id :component1))
                  (update-shape :main-child #(assoc % :fills (ths/sample-fills-color :fill-color "#333333")))
                  (sync-file :components (thi/id :component1)))]
    (t/is (= "#333333" (-> (ths/get-shape file' :copy-child) :fills first :fill-color)))))

;; ---- Typographies

(defn- typography-text
  "A text \"hello\" styled, on every node, with the library typography
   :typography1, as applying a typography in the app does."
  [font-size]
  (-> (txt/change-text (:content (ths/sample-shape :tmp :type :text)) "hello")
      (->> (txt/transform-nodes txt/is-content-node?
                                #(assoc %
                                        :font-size font-size
                                        :typography-ref-id (thi/id :typography1)
                                        :typography-ref-file (thi/id :file1))))))

(defn- component-using-typography
  "A component whose child is a text styled with the library typography
   :typography1 (font size 14), and a copy of it."
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-typography :typography1 :font-size "14")
      (tho/add-frame-with-text :main-root :main-child "hello"
                               :child-params {:content (typography-text "14")})
      (thc/make-component :component1 :main-root)
      (thc/instantiate-component :component1 :copy-root :children-labels [:copy-child])))

(defn- font-size-of
  "The font size of the first span of the text `shape`."
  [shape]
  (get-in shape [:content :children 0 :children 0 :children 0 :font-size]))

(defn- change-library-typography
  [file font-size]
  (-> file
      (update :data cttl/update-typography (thi/id :typography1) assoc :font-size font-size)
      (sync-file :typographies (thi/id :typography1))))

(t/deftest library-typography-sync-reaches-the-copy-untouched
  (let [file' (change-library-typography (component-using-typography) "24")
        copy  (ths/get-shape file' :copy-child)]
    (t/is (= "24" (font-size-of copy)))
    ;; a library sync is a derived write
    (t/is (nil? (:touched copy)))))

(t/deftest main-font-edit-reaches-the-copy-after-a-library-typography-sync
  (let [file' (-> (component-using-typography)
                  (change-library-typography "24")
                  (sync-file :components (thi/id :component1))
                  (update-shape :main-child
                                (fn [shape]
                                  (update shape :content
                                          #(txt/transform-nodes txt/is-content-node? (fn [node] (assoc node :font-size "40")) %))))
                  (sync-file :components (thi/id :component1)))]
    (t/is (= "40" (font-size-of (ths/get-shape file' :copy-child))))))

(t/deftest library-typography-sync-writes-only-text-styles
  (let [file' (change-library-typography (component-using-typography) "24")
        node  (get-in (ths/get-shape file' :main-child) [:content :children 0 :children 0 :children 0])]
    (t/is (not (contains? node :modified-at)))))
