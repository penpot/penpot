;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns frontend-tests.logic.comp-remove-swap-slots-test
  (:require
   [app.common.files.validate :as cfv]
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


(t/deftest test-keep-inherited-swap-slot-copy-paste-nested-copy-to-root
  (t/async
    done
    (let [;; ==== Setup
          ;; {:frame-red} [:name frame-red]                      # [Component :red]
          ;; {:frame-blue} [:name frame-blue]                    # [Component :blue]
          ;; {:frame-icon} [:name frame-icon]                    # [Component :icon]
          ;;     :red-copy-icon [:name frame-red]                @--> frame-red
          ;; {:frame-title} [:name frame-title]                  # [Component :title]
          ;;     :icon-copy-title [:name frame-icon]             @--> frame-icon
          ;;         :blue-copy-title [:name frame-blue, :swap-slot-label :red-copy-icon] @--> frame-blue
          ;; :title-copy [:name frame-title]                     #--> frame-title
          ;;     :icon-copy-in-title-copy [:name frame-icon]     @--> :icon-copy-title
          ;;         :blue-copy-in-title-copy [:name frame-blue] @--> :blue-copy-title
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red :name "frame-red")
                       (cthc/make-component :red :frame-red)
                       (ctho/add-frame :frame-blue :name "frame-blue")
                       (cthc/make-component :blue :frame-blue)
                       (ctho/add-frame :frame-icon :name "frame-icon")
                       (cthc/make-component :icon :frame-icon)
                       (cthc/instantiate-component :red :red-copy-icon :parent-label :frame-icon)
                       (ctho/add-frame :frame-title :name "frame-title")
                       (cthc/make-component :title :frame-title)
                       (cthc/instantiate-component :icon :icon-copy-title :parent-label :frame-title
                                                   :children-labels [:red-copy-in-icon-copy-title])
                       (cthc/component-swap :red-copy-in-icon-copy-title :blue :blue-copy-title)
                       (cthc/instantiate-component :title :title-copy
                                                   :children-labels [:icon-copy-in-title-copy
                                                                     :blue-copy-in-title-copy]))
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          icon     (cths/get-shape file :icon-copy-in-title-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id icon)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'        (ths/get-file-from-state new-state)
               page'        (cthf/current-page file')
               icon'        (cths/get-shape file' :icon-copy-in-title-copy)
               blue'        (cths/get-shape file' :blue-copy-in-title-copy)
               copied-icon' (find-copied-shape icon' page' uuid/zero)
               copied-blue' (find-copied-shape blue' page' (:id copied-icon'))
               errors       (try
                              (cfv/validate-file! file' {(:id file') file'})
                              nil
                              (catch :default cause
                                (ex-data cause)))]

           ;; ==== Check
           ;; the copied icon now refers to the icon main, where blue
           ;; does not exist, so the copied blue must keep the swap slot
           ;; inherited from :blue-copy-title
           (t/is (some? copied-icon'))
           (t/is (= (:shape-ref copied-icon') (cthi/id :frame-icon)))
           (t/is (some? copied-blue'))
           (t/is (= (ctk/get-swap-slot copied-blue') (cthi/id :red-copy-icon)))

           ;; the file must be valid after the paste
           (t/is (nil? errors))))))))

(t/deftest test-keep-touched-copy-paste-nested-copy-to-root
  (t/async
    done
    (let [;; ==== Setup
          ;; Add a nested copy inside a copy of :green, and override its fill
          ;; so that the copy has a touched attribute.
          file     (-> (setup-file)
                       (cthc/instantiate-component :green :green-copy-2
                                                   :parent-label :frame-b2
                                                   :children-labels [:red-copy-in-green-copy-2])
                       (cths/update-shape :red-copy-in-green-copy-2
                                          :fills (cths/sample-fills-color :fill-color "#ff0000")))
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          red      (cths/get-shape file :red-copy-in-green-copy-2)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id red)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'       (ths/get-file-from-state new-state)
               page'       (cthf/current-page file')
               red'        (cths/get-shape file' :red-copy-in-green-copy-2)
               copied-red' (find-copied-shape red' page' uuid/zero)]

           ;; ==== Check
           ;; the touched fill override must be preserved in the pasted copy
           (t/is (some? copied-red'))
           (t/is (contains? (:touched copied-red') :fill-group))
           (t/is (= (:touched red') (:touched copied-red')))))))))

(t/deftest test-remove-swap-slot-copy-paste-nested-swapped-copy-to-root
  (t/async
    done
    (let [;; ==== Setup (sample file as is)
          file     (setup-file)
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          blue     (cths/get-shape file :blue-copy-in-green-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id blue)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               blue'         (cths/get-shape file' :blue-copy-in-green-copy)
               copied-blue'  (find-copied-shape blue' page' uuid/zero)]

           ;; ==== Check
           ;; the copied blue has no swap-id
           (t/is (some? copied-blue'))
           (t/is (nil? (ctk/get-swap-slot copied-blue')))))))))

(t/deftest test-keep-swapped-children-refs-copy-paste-nested-copy-to-root
  (t/async
    done
    (let [;; ==== Setup
          ;; {:frame-base} [:name frame-base]                    # [Component :base]
          ;; {:frame-effects} [:name frame-effects]              # [Component :effects]
          ;;     :base-in-effects [:name frame-base]             @--> frame-base
          ;; {:frame-plain} [:name frame-plain]                  # [Component :plain]
          ;; {:frame-inner} [:name frame-inner]                  # [Component :inner]
          ;;     :plain-in-inner [:name frame-plain]             @--> frame-plain
          ;; {:frame-outer} [:name frame-outer]                  # [Component :outer]
          ;;     :inner-in-outer [:name frame-inner]             @--> frame-inner
          ;;         :plain-in-inner-in-outer [:name frame-plain] @--> :plain-in-inner
          ;; :outer-copy [:name frame-outer]                     #--> frame-outer
          ;;     :inner-copy [:name frame-inner]                 @--> :inner-in-outer
          ;;         :effects-swapped [:name frame-effects, :swap-slot-label :plain-in-inner] @--> frame-effects
          ;;             :base-in-swapped [:name frame-base]     @--> :base-in-effects
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-base :name "frame-base")
                       (cthc/make-component :base :frame-base)
                       (ctho/add-frame :frame-effects :name "frame-effects")
                       (cthc/make-component :effects :frame-effects)
                       (cthc/instantiate-component :base :base-in-effects :parent-label :frame-effects)
                       (ctho/add-frame :frame-plain :name "frame-plain")
                       (cthc/make-component :plain :frame-plain)
                       (ctho/add-frame :frame-inner :name "frame-inner")
                       (cthc/make-component :inner :frame-inner)
                       (cthc/instantiate-component :plain :plain-in-inner :parent-label :frame-inner)
                       (ctho/add-frame :frame-outer :name "frame-outer")
                       (cthc/make-component :outer :frame-outer)
                       (cthc/instantiate-component :inner :inner-in-outer :parent-label :frame-outer
                                                   :children-labels [:plain-in-inner-in-outer])
                       (cthc/instantiate-component :outer :outer-copy
                                                   :children-labels [:inner-copy :plain-copy])
                       (cthc/component-swap :plain-copy :effects :effects-swapped
                                            :children-labels [:base-in-swapped]))
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          inner    (cths/get-shape file :inner-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id inner)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'           (ths/get-file-from-state new-state)
               page'           (cthf/current-page file')
               inner'          (cths/get-shape file' :inner-copy)
               effects'        (cths/get-shape file' :effects-swapped)
               base'           (cths/get-shape file' :base-in-swapped)
               copied-inner'   (find-copied-shape inner' page' uuid/zero)
               copied-effects' (find-copied-shape effects' page' (:id copied-inner'))
               copied-base'    (find-copied-shape base' page' (:id copied-effects'))
               errors          (try
                                 (cfv/validate-file! file' {(:id file') file'})
                                 nil
                                 (catch :default cause
                                   (ex-data cause)))]

           ;; ==== Check
           ;; the swap was made in the copied shape itself, so the children
           ;; of the swapped copy already point to the effects main and must
           ;; not be advanced
           (t/is (some? copied-inner'))
           (t/is (= (:shape-ref copied-inner') (cthi/id :frame-inner)))
           (t/is (some? copied-effects'))
           (t/is (= (:shape-ref copied-effects') (cthi/id :frame-effects)))
           (t/is (= (ctk/get-swap-slot copied-effects') (cthi/id :plain-in-inner)))
           (t/is (some? copied-base'))
           (t/is (= (:shape-ref copied-base') (cthi/id :base-in-effects)))

           ;; the file must be valid after the paste
           (t/is (nil? errors))))))))

(defn- setup-file-deep-nested-swap
  []
  ;; {:frame-base} [:name frame-base]                    # [Component :base]
  ;; {:frame-effects} [:name frame-effects]              # [Component :effects]
  ;;     :base-in-effects [:name frame-base]             @--> frame-base
  ;; {:frame-plain} [:name frame-plain]                  # [Component :plain]
  ;; {:frame-inner} [:name frame-inner]                  # [Component :inner]
  ;;     :plain-in-inner [:name frame-plain]             @--> frame-plain
  ;; {:frame-mid} [:name frame-mid]                      # [Component :mid]
  ;;     :inner-in-mid [:name frame-inner]               @--> frame-inner
  ;;         :plain-in-mid [:name frame-plain]           @--> :plain-in-inner
  ;; {:frame-top} [:name frame-top]                      # [Component :top]
  ;;     :mid-in-top [:name frame-mid]                   @--> frame-mid
  ;;         :inner-in-top [:name frame-inner]           @--> :inner-in-mid
  ;;             :effects-in-top [:name frame-effects, :swap-slot-label :plain-in-inner] @--> frame-effects
  ;;                 [:name frame-base]                  @--> :base-in-effects
  ;; :top-copy [:name frame-top]                         #--> frame-top
  ;;     :mid-copy [:name frame-mid]                     @--> :mid-in-top
  ;;         :inner-copy [:name frame-inner]             @--> :inner-in-top
  ;;             :effects-copy [:name frame-effects]     @--> :effects-in-top
  ;;                 :base-copy [:name frame-base]       @--> (base in :effects-in-top)
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :frame-base :name "frame-base")
      (cthc/make-component :base :frame-base)
      (ctho/add-frame :frame-effects :name "frame-effects")
      (cthc/make-component :effects :frame-effects)
      (cthc/instantiate-component :base :base-in-effects :parent-label :frame-effects)
      (ctho/add-frame :frame-plain :name "frame-plain")
      (cthc/make-component :plain :frame-plain)
      (ctho/add-frame :frame-inner :name "frame-inner")
      (cthc/make-component :inner :frame-inner)
      (cthc/instantiate-component :plain :plain-in-inner :parent-label :frame-inner)
      (ctho/add-frame :frame-mid :name "frame-mid")
      (cthc/make-component :mid :frame-mid)
      (cthc/instantiate-component :inner :inner-in-mid :parent-label :frame-mid
                                  :children-labels [:plain-in-mid])
      (ctho/add-frame :frame-top :name "frame-top")
      (cthc/make-component :top :frame-top)
      (cthc/instantiate-component :mid :mid-in-top :parent-label :frame-top
                                  :children-labels [:inner-in-top :plain-in-top])
      (cthc/component-swap :plain-in-top :effects :effects-in-top)
      (cthc/instantiate-component :top :top-copy
                                  :children-labels [:mid-copy :inner-copy
                                                    :effects-copy :base-copy])))

(t/deftest test-keep-swapped-children-refs-copy-paste-deep-nested-copy-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-deep-nested-swap)
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          inner    (cths/get-shape file :inner-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id inner)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'           (ths/get-file-from-state new-state)
               page'           (cthf/current-page file')
               inner'          (cths/get-shape file' :inner-copy)
               effects'        (cths/get-shape file' :effects-copy)
               base'           (cths/get-shape file' :base-copy)
               copied-inner'   (find-copied-shape inner' page' uuid/zero)
               copied-effects' (find-copied-shape effects' page' (:id copied-inner'))
               copied-base'    (find-copied-shape base' page' (:id copied-effects'))
               errors          (try
                                 (cfv/validate-file! file' {(:id file') file'})
                                 nil
                                 (catch :default cause
                                   (ex-data cause)))]

           ;; ==== Check
           ;; the swap was made in the top main, one of the two levels the
           ;; copied inner drops, so the children of the swapped copy must
           ;; advance only one level, up to the effects main
           (t/is (some? copied-inner'))
           (t/is (= (:shape-ref copied-inner') (cthi/id :frame-inner)))
           (t/is (some? copied-effects'))
           (t/is (= (:shape-ref copied-effects') (cthi/id :frame-effects)))
           (t/is (= (ctk/get-swap-slot copied-effects') (cthi/id :plain-in-inner)))
           (t/is (some? copied-base'))
           (t/is (= (:shape-ref copied-base') (cthi/id :base-in-effects)))

           ;; the file must be valid after the paste
           (t/is (nil? errors))))))))

(t/deftest test-keep-swapped-children-refs-copy-paste-swapped-head-to-root
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-deep-nested-swap)
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          effects  (cths/get-shape file :effects-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id effects)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'           (ths/get-file-from-state new-state)
               page'           (cthf/current-page file')
               effects'        (cths/get-shape file' :effects-copy)
               base'           (cths/get-shape file' :base-copy)
               copied-effects' (find-copied-shape effects' page' uuid/zero)
               copied-base'    (find-copied-shape base' page' (:id copied-effects'))
               errors          (try
                                 (cfv/validate-file! file' {(:id file') file'})
                                 nil
                                 (catch :default cause
                                   (ex-data cause)))]

           ;; ==== Check
           ;; the copied effects was swapped in the top main, so it and its
           ;; children advance only up to the effects main. As a root copy it
           ;; carries no swap slot
           (t/is (some? copied-effects'))
           (t/is (= (:shape-ref copied-effects') (cthi/id :frame-effects)))
           (t/is (nil? (ctk/get-swap-slot copied-effects')))
           (t/is (some? copied-base'))
           (t/is (= (:shape-ref copied-base') (cthi/id :base-in-effects)))

           ;; the file must be valid after the paste
           (t/is (nil? errors))))))))

(t/deftest test-keep-swap-slot-copy-paste-deep-nested-copy-of-deleted-component-to-root
  (t/async
    done
    (let [;; ==== Setup
          ;; same tree, with the :top component deleted, so the ref chain
          ;; of the copy goes through a deleted component
          file     (-> (setup-file-deep-nested-swap)
                       (ctho/delete-shape :frame-top))
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          inner    (cths/get-shape file :inner-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id inner)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'           (ths/get-file-from-state new-state)
               page'           (cthf/current-page file')
               inner'          (cths/get-shape file' :inner-copy)
               effects'        (cths/get-shape file' :effects-copy)
               base'           (cths/get-shape file' :base-copy)
               copied-inner'   (find-copied-shape inner' page' uuid/zero)
               copied-effects' (find-copied-shape effects' page' (:id copied-inner'))
               copied-base'    (find-copied-shape base' page' (:id copied-effects'))
               errors          (try
                                 (cfv/validate-file! file' {(:id file') file'})
                                 nil
                                 (catch :default cause
                                   (ex-data cause)))]

           ;; ==== Check
           ;; the swap slot comes from the deleted top component, so the
           ;; ref chain must follow deleted components to keep it
           (t/is (some? copied-effects'))
           (t/is (= (:shape-ref copied-effects') (cthi/id :frame-effects)))
           (t/is (= (ctk/get-swap-slot copied-effects') (cthi/id :plain-in-inner)))
           (t/is (some? copied-base'))
           (t/is (= (:shape-ref copied-base') (cthi/id :base-in-effects)))

           ;; the file must be valid after the paste
           (t/is (nil? errors))))))))

(defn- setup-file-swap-in-mid
  []
  ;; {:frame-base} [:name frame-base]                    # [Component :base]
  ;; {:frame-effects} [:name frame-effects]              # [Component :effects]
  ;;     :base-in-effects [:name frame-base]             @--> frame-base
  ;; {:frame-plain} [:name frame-plain]                  # [Component :plain]
  ;; {:frame-inner} [:name frame-inner]                  # [Component :inner]
  ;;     :plain-in-inner [:name frame-plain]             @--> frame-plain
  ;; {:frame-mid} [:name frame-mid]                      # [Component :mid]
  ;;     :inner-in-mid [:name frame-inner]               @--> frame-inner
  ;;         :effects-in-mid [:name frame-effects, :swap-slot-label :plain-in-inner] @--> frame-effects
  ;;             [:name frame-base]                      @--> :base-in-effects
  ;; {:frame-top} [:name frame-top]                      # [Component :top]
  ;;     :mid-in-top [:name frame-mid]                   @--> frame-mid
  ;;         :inner-in-top [:name frame-inner]           @--> :inner-in-mid
  ;;             :effects-in-top [:name frame-effects]   @--> :effects-in-mid
  ;;                 [:name frame-base]                  @--> (base in :effects-in-mid)
  ;; :top-copy [:name frame-top]                         #--> frame-top
  ;;     :mid-copy [:name frame-mid]                     @--> :mid-in-top
  ;;         :inner-copy [:name frame-inner]             @--> :inner-in-top
  ;;             :effects-copy [:name frame-effects]     @--> :effects-in-top
  ;;                 :base-copy [:name frame-base]       @--> (base in :effects-in-top)
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :frame-base :name "frame-base")
      (cthc/make-component :base :frame-base)
      (ctho/add-frame :frame-effects :name "frame-effects")
      (cthc/make-component :effects :frame-effects)
      (cthc/instantiate-component :base :base-in-effects :parent-label :frame-effects)
      (ctho/add-frame :frame-plain :name "frame-plain")
      (cthc/make-component :plain :frame-plain)
      (ctho/add-frame :frame-inner :name "frame-inner")
      (cthc/make-component :inner :frame-inner)
      (cthc/instantiate-component :plain :plain-in-inner :parent-label :frame-inner)
      (ctho/add-frame :frame-mid :name "frame-mid")
      (cthc/make-component :mid :frame-mid)
      (cthc/instantiate-component :inner :inner-in-mid :parent-label :frame-mid
                                  :children-labels [:plain-in-mid])
      (cthc/component-swap :plain-in-mid :effects :effects-in-mid)
      (ctho/add-frame :frame-top :name "frame-top")
      (cthc/make-component :top :frame-top)
      (cthc/instantiate-component :mid :mid-in-top :parent-label :frame-top
                                  :children-labels [:inner-in-top :effects-in-top])
      (cthc/instantiate-component :top :top-copy
                                  :children-labels [:mid-copy :inner-copy
                                                    :effects-copy :base-copy])))

(t/deftest test-keep-swapped-children-refs-copy-paste-swap-at-last-dropped-level
  (t/async
    done
    (let [;; ==== Setup
          file     (setup-file-swap-in-mid)
          store    (ths/setup-store file)

          ;; ==== Action
          page     (cthf/current-page file)
          inner    (cths/get-shape file :inner-copy)
          features #{"components/v2"}
          version  47

          pdata    (thp/simulate-copy-shape #{(:id inner)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape uuid/zero)
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'           (ths/get-file-from-state new-state)
               page'           (cthf/current-page file')
               inner'          (cths/get-shape file' :inner-copy)
               effects'        (cths/get-shape file' :effects-copy)
               base'           (cths/get-shape file' :base-copy)
               copied-inner'   (find-copied-shape inner' page' uuid/zero)
               copied-effects' (find-copied-shape effects' page' (:id copied-inner'))
               copied-base'    (find-copied-shape base' page' (:id copied-effects'))
               errors          (try
                                 (cfv/validate-file! file' {(:id file') file'})
                                 nil
                                 (catch :default cause
                                   (ex-data cause)))]

           ;; ==== Check
           ;; the swap was made in the mid main, the last of the two levels
           ;; the copied inner drops, so the children of the swapped copy
           ;; advance both levels, up to the effects main
           (t/is (some? copied-inner'))
           (t/is (= (:shape-ref copied-inner') (cthi/id :frame-inner)))
           (t/is (some? copied-effects'))
           (t/is (= (:shape-ref copied-effects') (cthi/id :frame-effects)))
           (t/is (= (ctk/get-swap-slot copied-effects') (cthi/id :plain-in-inner)))
           (t/is (some? copied-base'))
           (t/is (= (:shape-ref copied-base') (cthi/id :base-in-effects)))

           ;; the file must be valid after the paste
           (t/is (nil? errors))))))))
