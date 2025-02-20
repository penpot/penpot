;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.logic.copying-and-duplicating-test
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

;; Related .penpot file: common/test/cases/copying-and-duplicating.penpot
(defn- setup-file []
  (-> (cthf/sample-file :file1 :page-label :page-1)
      (ctho/add-simple-component :simple-1 :frame-simple-1 :rect-simple-1 :child-params {:type :rect :fills (cths/sample-fills-color :fill-color "#2152e5") :name "rect-simple-1"})

      (ctho/add-frame :frame-composed-1 :name "frame-composed-1")
      (cthc/instantiate-component :simple-1 :copy-simple-1 :parent-label :frame-composed-1 :children-labels [:composed-1-simple-1])
      (cths/add-sample-shape :rect-composed-1 :parent-label :frame-composed-1 :fills (cths/sample-fills-color :fill-color "#B1B2B5"))
      (cthc/make-component :composed-1 :frame-composed-1)

      (ctho/add-frame :frame-composed-2 :name "frame-composed-2")
      (cthc/instantiate-component :composed-1 :copy-composed-1-composed-2 :parent-label :frame-composed-2 :children-labels [:composed-1-composed-2])
      (cthc/make-component :composed-2 :frame-composed-2)

      (cthc/instantiate-component :composed-2 :copy-composed-2)

      (ctho/add-frame :frame-composed-3 :name "frame-composed-3")
      (ctho/add-group :group-3 :parent-label :frame-composed-3)
      (cthc/instantiate-component :composed-2 :copy-composed-1-composed-3 :parent-label :group-3 :children-labels [:composed-1-composed-2])
      (cths/add-sample-shape :circle-composed-3 :parent-label :group-3 :fills (cths/sample-fills-color :fill-color "#B1B2B5"))
      (cthc/make-component :composed-3 :frame-composed-3)

      (cthc/instantiate-component :composed-3 :copy-composed-3 :children-labels [:composed-2-composed-3])
      (cthf/add-sample-page :page-2)
      (cthf/switch-to-page :page-1)))


(defn- copy-paste-shape
  [id file & {:keys [target-page-label target-container-id]}]
  (let [features            #{"components/v2"}
        version             46
        page                (cthf/current-page file)
        target-page-id      (cthi/id target-page-label)
        shape               (if (keyword? id)
                              (cths/get-shape file id)
                              (cths/get-shape-by-id file id))
        pdata               (thp/simulate-copy-shape #{(:id shape)} (:objects page) {(:id  file) file} page file features version)
        target-container-id (or target-container-id (:parent-id shape))]

    (filter some?
            [(when target-page-id (dw/initialize-page (:id file) target-page-id))
             (dws/select-shape target-container-id)
             (dw/paste-shapes pdata)
             (when target-page-id (dw/initialize-page (:id file) (:id page)))])))

(defn- sync-file [file]
  (map (fn [component-tag]
         (->> component-tag
              (cthc/get-component file)
              :component-id
              (dwl/sync-file (:id file) (:id file) :components)))
       [:simple-1 :composed-1 :composed-2 :composed-3]))

(defn- set-color-bottom-shape [label file color]
  (let [shape (ctho/bottom-shape file label)]
    (concat
     [(dws/select-shape (:id shape))
      (dc/apply-color-from-palette color false)]
     (sync-file file))))

(defn- count-shapes [file name color]
  (let [page (cthf/current-page file)]
    (->> (vals (:objects page))
         (filter #(and
                   (= (:name %) name)
                   (-> (cths/get-shape-by-id file (:id %))
                       :fills
                       first
                       :fill-color
                       (= color))))
         (count))))

(defn- duplicate-each-main-and-first-level-copy [file]
  (concat (copy-paste-shape :frame-simple-1 file)
          (copy-paste-shape :frame-simple-1 file)
          (copy-paste-shape :frame-composed-1 file)
          (copy-paste-shape :frame-composed-1 file)
          (copy-paste-shape :frame-composed-2 file)
          (copy-paste-shape :frame-composed-2 file)
          (copy-paste-shape :frame-composed-3 file)
          (copy-paste-shape :frame-composed-3 file)
          (copy-paste-shape :copy-composed-2 file)
          (copy-paste-shape :copy-composed-2 file)
          (copy-paste-shape :copy-composed-3 file)
          (copy-paste-shape :copy-composed-3 file)))

(defn- duplicate-simple-nested-in-main-and-group [file]
  (concat (copy-paste-shape :copy-simple-1 file)
          (copy-paste-shape :copy-simple-1 file)
          (copy-paste-shape :group-3 file)
          (copy-paste-shape :group-3 file)))

(defn- duplicate-copy-nested-and-group-out-of-the-main
  [file & {:keys [target-page-label]}]
  (let [page (cthf/current-page file)
        frame-1-instance-ids (->> (vals (:objects page))
                                  (filter #(and
                                            (or
                                             (= (:name %) "Frame1")
                                             (= (:name %) "Group1"))
                                            (not (:component-root %))))
                                  (map :id))]
    (concat
     (apply concat
            (mapv #(copy-paste-shape % file :target-page-label target-page-label :target-container-id uuid/zero) frame-1-instance-ids))
     (apply concat
            (mapv #(copy-paste-shape % file :target-page-label target-page-label :target-container-id uuid/zero) frame-1-instance-ids)))))

(t/deftest main-and-first-level-copy-1
  (t/async done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
            ;; ==== Action


            ;; For each main and first level copy:
            ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
             ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file'         (ths/get-file-from-state new-state)]
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 18)))))))))

(t/deftest main-and-first-level-copy-2
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
         ;; ==== Action


         ;; For each main and first level copy:
         ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
           ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
            ;; - Change color of the nearest main and check propagation to duplicated.
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file'         (ths/get-file-from-state new-state)]
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 15)))))))))

(t/deftest main-and-first-level-copy-3
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
         ;; ==== Action


         ;; For each main and first level copy:
         ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
           ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
            ;; - Change color of the nearest main and check propagation to duplicated.
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :frame-composed-2 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file'         (ths/get-file-from-state new-state)]
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 12)))))))))


(t/deftest main-and-first-level-copy-4
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)
         ;; ==== Action


         ;; For each main and first level copy:
         ;; - Duplicate it two times with copy-paste.
            events
            (concat
             (duplicate-each-main-and-first-level-copy file)
           ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
            ;; - Change color of the nearest main and check propagation to duplicated.
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :frame-composed-2 file {:color "#333333"})
             (set-color-bottom-shape :frame-composed-3 file {:color "#444444"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file'         (ths/get-file-from-state new-state)]
             (t/is (= (count-shapes file' "rect-simple-1" "#444444") 6)))))))))

(t/deftest copy-nested-in-main-1
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
            ;; - Duplicate it two times, keeping the duplicated inside the same main.
            events
            (concat
             (duplicate-simple-nested-in-main-and-group file)
              ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
              ;; Check propagation to all copies.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 28)))))))))

(t/deftest copy-nested-in-main-2
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
            ;; - Duplicate it two times, keeping the duplicated inside the same main.
            events
            (concat
             (duplicate-simple-nested-in-main-and-group file)
              ;; - Change color of the nearest main
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
              ;; Check propagation to duplicated.
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 9)))))))))

(t/deftest copy-nested-in-main-3
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main, and the group inside Composed3 main:
            ;; - Duplicate it two times, keeping the duplicated inside the same main.
            events
            (concat
             (duplicate-simple-nested-in-main-and-group file)
              ;; - Change color of the copy you duplicated from.
             (set-color-bottom-shape :group-3 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
              ;; Check that it's NOT PROPAGATED.
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 2)))))))))

(t/deftest copy-nested-1
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main or other copy, and the group inside Composed3
            ;; main and copy:
            ;;   - Duplicate it two times, moving the duplicates out of the main.
            events
            (concat
             (duplicate-copy-nested-and-group-out-of-the-main file)
              ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
              ;; Check propagation to all copies.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 20)))))))))


(t/deftest copy-nested-2
  (t/async
    done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main or other copy, and the group inside Composed3
            ;; main and copy:
            ;;   - Duplicate it two times, moving the duplicates out of the main.
            events
            (concat
             (duplicate-copy-nested-and-group-out-of-the-main file)
              ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
              ;; - Change color of the previous main
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :group-3 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (ths/get-file-from-state new-state)]
              ;; Check that it's NOT PROPAGATED.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 11))
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 7))
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 2)))))))))


(t/deftest copy-nested-3
  (t/async done
    (with-redefs [uuid/next cthi/next-uuid]
      (let [;; ==== Setup
            file     (setup-file)
            store    (ths/setup-store file)

            ;; ==== Action
            ;; For each copy of Simple1 nested in a main or other copy, and the group inside Composed3
            ;; main and copy:
            ;;   - Duplicate it two times, moving the duplicates to another page
            events
            (concat
             (duplicate-copy-nested-and-group-out-of-the-main file :target-page-label :page-2)
              ;; - Change color of Simple1
             (set-color-bottom-shape :frame-simple-1 file {:color "#111111"})
              ;; - Change color of the previous main
             (set-color-bottom-shape :frame-composed-1 file {:color "#222222"})
             (set-color-bottom-shape :group-3 file {:color "#333333"}))]

        (ths/run-store
         store done events
         (fn [new-state]
           (let [file' (-> (ths/get-file-from-state new-state)
                           (cthf/switch-to-page :page-2))]
              ;; Check that it's NOT PROPAGATED.
             (t/is (= (count-shapes file' "rect-simple-1" "#111111") 10))
             (t/is (= (count-shapes file' "rect-simple-1" "#222222") 4))
             (t/is (= (count-shapes file' "rect-simple-1" "#333333") 0)))))))))
