;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns frontend-tests.logic.comp-remove-swap-slots-test
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.selection :as dws]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

;; Related .penpot file: common/test/cases/remove-swap-slots.penpot
(defn- setup-file
  []
  ;; {:frame-red} [:name Frame1]                           # [Component :red]
  ;; {:frame-blue} [:name Frame1]                          # [Component :blue]
  ;; {:frame-green} [:name Frame1]                         # [Component :green]
  ;;     :red-copy-green [:name Frame1]                    @--> :frame-red
  ;; {:frame-b1} [:name Frame1]                            # [Component :b1]
  ;;     :blue1 [:name Frame1, :swap-slot-label :red-copy] @--> :frame-blue
  ;;     :frame-yellow [:name Frame1]
  ;;     :green-copy [:name Frame1]                        @--> :frame-green
  ;;         :blue-copy-in-green-copy [:name Frame1, :swap-slot-label :red-copy-green] @--> :frame-blue
  ;; {:frame-b2} [:name Frame1]                            # [Component :b2]
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :frame-red)
      (cthc/make-component :red :frame-red)
      (ctho/add-frame :frame-blue :name "frame-blue")
      (cthc/make-component :blue :frame-blue)
      (ctho/add-frame :frame-green :name "frame-green")
      (cthc/make-component :green :frame-green)
      (cthc/instantiate-component :red :red-copy-green :parent-label :frame-green)
      (ctho/add-frame :frame-b1)
      (cthc/make-component :b1 :frame-b1)
      (ctho/add-frame :frame-yellow :parent-label :frame-b1 :name "frame-yellow")
      (cthc/instantiate-component :red :red-copy :parent-label :frame-b1)
      (cthc/component-swap :red-copy :blue :blue1)
      (cthc/instantiate-component :green :green-copy :parent-label :frame-b1 :children-labels [:red-copy-in-green-copy])
      (cthc/component-swap :red-copy-in-green-copy :blue :blue-copy-in-green-copy)
      (ctho/add-frame :frame-b2)
      (cthc/make-component :b2 :frame-b2)))


(defn- setup-file-blue1-in-yellow
  []
  ;; {:frame-red} [:name Frame1]                           # [Component :red]
  ;; {:frame-blue} [:name Frame1]                          # [Component :blue]
  ;; {:frame-green} [:name Frame1]                         # [Component :green]
  ;;     :red-copy-green [:name Frame1]                    @--> :frame-red
  ;; {:frame-b1} [:name Frame1]                            # [Component :b1]
  ;;     :frame-yellow [:name Frame1]
  ;;         :blue1 [:name Frame1, :swap-slot-label :red-copy] @--> :frame-blue
  ;;     :green-copy [:name Frame1]                        @--> :frame-green
  ;;         :blue-copy-in-green-copy [:name Frame1, :swap-slot-label :red-copy-green] @--> :frame-blue
  ;; {:frame-b2} [:name Frame1]                            # [Component :b2]
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :frame-red)
      (cthc/make-component :red :frame-red)
      (ctho/add-frame :frame-blue :name "frame-blue")
      (cthc/make-component :blue :frame-blue)
      (ctho/add-frame :frame-green)
      (cthc/make-component :green :frame-green)
      (cthc/instantiate-component :red :red-copy-green :parent-label :frame-green)
      (ctho/add-frame :frame-b1)
      (cthc/make-component :b1 :frame-b1)
      (ctho/add-frame :frame-yellow :parent-label :frame-b1 :name "frame-yellow")
      (cthc/instantiate-component :red :red-copy :parent-label :frame-yellow)
      (cthc/component-swap :red-copy :blue :blue1)
      (cthc/instantiate-component :green :green-copy :parent-label :frame-b1 :children-labels [:red-copy-in-green-copy])
      (cthc/component-swap :red-copy-in-green-copy :blue :blue-copy-in-green-copy)
      (ctho/add-frame :frame-b2)
      (cthc/make-component :b2 :frame-b2)))

(defn- find-copied-shape
  [original-shape page parent-id]
  ;; copied shape has the same name, is in the specified parent, and doesn't have a label
  (->> (vals (:objects page))
       (filter #(and (= (:name %) (:name original-shape))
                     (= (:parent-id %) parent-id)
                     (str/starts-with? (cthi/label (:id %)) "<no-label")))
       first))

(t/deftest test-remove-swap-slot-copy-paste-blue1-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               blue1'        (cths/get-shape file' :blue1)
               copied-blue1' (find-copied-shape blue1' page' uuid/zero)]

          ;; ==== Check

          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))


(t/deftest test-remove-swap-slot-copy-paste-blue1-to-b1
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b1       (cths/get-shape file :frame-b1)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id b1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               b1'           (cths/get-shape file' :frame-b1)
               blue1'        (cths/get-shape file' :blue1)
               copied-blue1' (find-copied-shape blue1' page' (:id b1'))]

          ;; ==== Check
          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-remove-swap-slot-copy-paste-blue1-to-yellow
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id yellow))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               yellow'       (cths/get-shape file' :frame-yellow)
               blue1'        (cths/get-shape file' :blue1)
               copied-blue1' (find-copied-shape blue1' page' (:id yellow'))]

          ;; ==== Check
          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))


(t/deftest test-remove-swap-slot-copy-paste-blue1-to-b2
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b2       (cths/get-shape file :frame-b2)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id b2))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               b2'           (cths/get-shape file' :frame-b2)
               blue1'        (cths/get-shape file' :blue1)
               copied-blue1' (find-copied-shape blue1' page' (:id b2'))]

          ;; ==== Check
          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))


(t/deftest test-remove-swap-slot-cut-paste-blue1-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id blue1))
           (dw/delete-selected)
           (dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               copied-blue1' (find-copied-shape blue1 page' uuid/zero)]

          ;; ==== Check
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))



(t/deftest test-remove-swap-slot-cut-paste-blue1-to-yellow
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id blue1))
           (dw/delete-selected)
           (dws/select-shape (:id yellow))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               yellow'       (cths/get-shape file' :frame-yellow)
               copied-blue1' (find-copied-shape blue1 page' (:id yellow'))]

          ;; ==== Check
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))


(t/deftest test-remove-swap-slot-cut-paste-blue1-to-b2
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b2       (cths/get-shape file :frame-b2)
          blue1    (cths/get-shape file :blue1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id blue1)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id blue1))
           (dw/delete-selected)
           (dws/select-shape (:id b2))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               b2'           (cths/get-shape file' :frame-b2)
               copied-blue1' (find-copied-shape blue1 page' (:id b2'))]

          ;; ==== Check
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))


(t/deftest test-remove-swap-slot-copy-paste-yellow-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-blue1-in-yellow)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id yellow)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               copied-yellow' (find-copied-shape yellow page' uuid/zero)
               blue1'         (cths/get-shape file' :blue1)
               copied-blue1'  (find-copied-shape blue1' page' (:id copied-yellow'))]

          ;; ==== Check

          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-remove-swap-slot-copy-paste-yellow-to-b1
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-blue1-in-yellow)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          b1       (cths/get-shape file :frame-b1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id yellow)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id b1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b1'            (cths/get-shape file' :frame-b1)
               copied-yellow' (find-copied-shape yellow page' (:id b1'))
               blue1'         (cths/get-shape file' :blue1)
               copied-blue1'  (find-copied-shape blue1' page' (:id copied-yellow'))]

          ;; ==== Check

          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-remove-swap-slot-copy-paste-yellow-to-b2
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-blue1-in-yellow)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          b2       (cths/get-shape file :frame-b2)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id yellow)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id b2))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b2'            (cths/get-shape file' :frame-b2)
               copied-yellow' (find-copied-shape yellow page' (:id b2'))
               blue1'         (cths/get-shape file' :blue1)
               copied-blue1'  (find-copied-shape blue1' page' (:id copied-yellow'))]

          ;; ==== Check

          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-remove-swap-slot-cut-paste-yellow-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-blue1-in-yellow)
          store    (ths/setup-store file)
          blue1    (cths/get-shape file :blue1)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id yellow)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id yellow))
           (dw/delete-selected)
           (dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               copied-yellow' (find-copied-shape yellow page' uuid/zero)
               copied-blue1'  (find-copied-shape blue1 page' (:id copied-yellow'))]

          ;; ==== Check
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-remove-swap-slot-cut-paste-yellow-to-b1
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-blue1-in-yellow)
          store    (ths/setup-store file)
          blue1    (cths/get-shape file :blue1)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          b1       (cths/get-shape file :frame-b1)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id yellow)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id yellow))
           (dw/delete-selected)
           (dws/select-shape (:id b1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b1'            (cths/get-shape file' :frame-b1)
               copied-yellow' (find-copied-shape yellow page' (:id b1'))
               copied-blue1'  (find-copied-shape blue1 page' (:id copied-yellow'))]

          ;; ==== Check
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-remove-swap-slot-cut-paste-yellow-to-b2
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-blue1-in-yellow)
          store    (ths/setup-store file)
          blue1    (cths/get-shape file :blue1)

         ;; ==== Action
          page     (cthf/current-page file)
          yellow   (cths/get-shape file :frame-yellow)
          b2       (cths/get-shape file :frame-b2)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id yellow)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id yellow))
           (dw/delete-selected)
           (dws/select-shape (:id b2))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b2'            (cths/get-shape file' :frame-b2)
               copied-yellow' (find-copied-shape yellow page' (:id b2'))
               copied-blue1'  (find-copied-shape blue1 page' (:id copied-yellow'))]

          ;; ==== Check
          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-keep-swap-slot-copy-paste-green-copy-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          green    (cths/get-shape file :green-copy)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               blue2'         (cths/get-shape file' :blue-copy-in-green-copy)
               copied-green'  (find-copied-shape green page' uuid/zero)
               copied-blue2'  (find-copied-shape blue2' page' (:id copied-green'))]

          ;; ==== Check

          ;; blue2 has swap-id
           (t/is (some? (ctk/get-swap-slot blue2')))

          ;; copied-blue2 also has swap-id
           (t/is (some? copied-blue2'))
           (t/is (some? (ctk/get-swap-slot copied-blue2')))))))))

(t/deftest test-keep-swap-slot-copy-paste-green-copy-to-b1
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b1       (cths/get-shape file :frame-b1)
          green    (cths/get-shape file :green-copy)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id b1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b1'            (cths/get-shape file' :frame-b1)
               blue2'         (cths/get-shape file' :blue-copy-in-green-copy)
               copied-green'  (find-copied-shape green page' (:id b1'))
               copied-blue2'  (find-copied-shape blue2' page' (:id copied-green'))]

          ;; ==== Check

          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue2')))

          ;; copied-blue1 also has swap-id
           (t/is (some? copied-blue2'))
           (t/is (some? (ctk/get-swap-slot copied-blue2')))))))))

(t/deftest test-keep-swap-slot-copy-paste-green-copy-to-b2
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b2       (cths/get-shape file :frame-b2)
          green    (cths/get-shape file :green-copy)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id b2))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b2'            (cths/get-shape file' :frame-b2)
               blue2'         (cths/get-shape file' :blue-copy-in-green-copy)
               copied-green'  (find-copied-shape green page' (:id b2'))
               copied-blue2'  (find-copied-shape blue2' page' (:id copied-green'))]

          ;; ==== Check

          ;; blue2 has swap-id
           (t/is (some? (ctk/get-swap-slot blue2')))

          ;; copied-blue1 also has swap-id
           (t/is (some? copied-blue2'))
           (t/is (some? (ctk/get-swap-slot copied-blue2')))))))))


(t/deftest test-keep-swap-slot-cut-paste-green-copy-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          green    (cths/get-shape file :green-copy)
          blue2    (cths/get-shape file :blue-copy-in-green-copy)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id green))
           (dw/delete-selected)
           (dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               copied-green'  (find-copied-shape green page' uuid/zero)
               copied-blue1'  (find-copied-shape blue2 page' (:id copied-green'))]

          ;; ==== Check
          ;; copied-blue1 has swap-id
           (t/is (some? copied-blue1'))
           (t/is (some? (ctk/get-swap-slot copied-blue1')))))))))

(t/deftest test-keep-swap-slot-cut-paste-green-copy-to-b1
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b1       (cths/get-shape file :frame-b1)
          green    (cths/get-shape file :green-copy)
          blue2    (cths/get-shape file :blue-copy-in-green-copy)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id green))
           (dw/delete-selected)
           (dws/select-shape (:id b1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b1'            (cths/get-shape file' :frame-b1)
               copied-green'  (find-copied-shape green page' (:id b1'))
               copied-blue2'  (find-copied-shape blue2 page' (:id copied-green'))]

          ;; ==== Check
          ;; copied-blue1 has swap-id
           (t/is (some? copied-blue2'))
           (t/is (some? (ctk/get-swap-slot copied-blue2')))))))))

(t/deftest test-keep-swap-slot-cut-paste-green-copy-to-b2
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file)
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          b2       (cths/get-shape file :frame-b2)
          blue2    (cths/get-shape file :blue-copy-in-green-copy)
          green    (cths/get-shape file :green-copy)
          features #{"components/v2"}
          version  46

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id green))
           (dw/delete-selected)
           (dws/select-shape (:id b2))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               page'          (cthf/current-page file')
               b2'            (cths/get-shape file' :frame-b2)
               copied-green'  (find-copied-shape green page' (:id b2'))
               copied-blue2'  (find-copied-shape blue2 page' (:id copied-green'))]

          ;; ==== Check
          ;; copied-blue1 has swap-id
           (t/is (some? copied-blue2'))
           (t/is (some? (ctk/get-swap-slot copied-blue2')))))))))



(t/deftest test-remove-swap-slot-copy-paste-swapped-main
  (t/async
    done
    (let [;; ==== Setup
         ;; {:frame-red} [:name frame-blue]                    # [Component :red]
         ;; {:frame-blue} [:name frame-blue]                   #[Component :blue]
         ;; {:frame-green} [:name frame-green]                 #[Component :green]
         ;;     :blue1 [:name frame-blue, :swap-slot-label :red-copy-green] @--> frame-blue

          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red :name "frame-blue")
                       (cthc/make-component :red :frame-red)
                       (ctho/add-frame :frame-blue :name "frame-blue")
                       (cthc/make-component :blue :frame-blue)
                       (ctho/add-frame :frame-green :name "frame-green")
                       (cthc/make-component :green :frame-green)
                       (cthc/instantiate-component :red :red-copy-green :parent-label :frame-green)
                       (cthc/component-swap :red-copy-green :blue :blue1))
          store    (ths/setup-store file)

         ;; ==== Action
          page     (cthf/current-page file)
          green    (cths/get-shape file :frame-green)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id green)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               green'        (cths/get-shape file' :frame-green)
               blue1'        (cths/get-shape file' :blue1)
               copied-green' (find-copied-shape green' page' uuid/zero)
               copied-blue1' (find-copied-shape blue1' page' (:id copied-green'))]

          ;; ==== Check
          ;; blue1 has swap-id
           (t/is (some? (ctk/get-swap-slot blue1')))

          ;; copied-blue1 has not swap-id
           (t/is (some? copied-blue1'))
           (t/is (nil? (ctk/get-swap-slot copied-blue1')))))))))

