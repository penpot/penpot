;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
(ns frontend-tests.logic.pasting-in-containers-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.variants :as thv]
   [app.common.transit :as transit]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.svg-upload :as dwsvg]
   [app.main.streams :as ms]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(defonce ^:private original-navigator-clipboard
  (unchecked-get js/navigator "clipboard"))

(defn- restore-navigator-clipboard!
  []
  (unchecked-set js/navigator "clipboard" original-navigator-clipboard))

(defn- install-read-clipboard!
  "Install a `navigator.clipboard` stub serving `text` as a single
  text/plain item, so `from-navigator` resolves it through the usual
  transit decoding."
  [text]
  (unchecked-set
   js/navigator "clipboard"
   #js {:read (fn []
                (js/Promise.resolve
                 #js [#js {:types #js ["text/plain"]
                           :getType (fn [_mime]
                                      (js/Promise.resolve
                                       #js {:size (count text)
                                            :text (fn [] (js/Promise.resolve text))}))}]))}))

(t/use-fixtures :each
  {:before thp/reset-idmap!
   :after restore-navigator-clipboard!})

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
      (ctho/add-frame :frame-red {:name "frame-red"})
      (ctho/add-frame :frame-red {:name "frame-red"})
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

(defn- find-copied-shape
  ([original-shape page parent-id]
   (find-copied-shape original-shape page parent-id false))
  ([original-shape page parent-id ignore-label?]
   ;; copied shape has the same name, is in the specified parent, and doesn't have a label
   ;; for restored components we can ignore the label part
   (->> (vals (:objects page))
        (filter #(and (= (:name %) (:name original-shape))
                      (= (:parent-id %) parent-id)
                      (or ignore-label?  (str/starts-with? (cthi/label (:id %)) "<no-label"))))
        first)))

(t/deftest copy-shape-to-frame
  "Coping a rect into a frame results in a copy of the rect inside the frame"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (ctho/add-frame :frame-blue {:name "frame-blue"}))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          frame-red  (cths/get-shape file :frame-red)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               frame-red'    (cths/get-shape file' :frame-red)
               frame-blue'   (cths/get-shape file' :frame-blue)
               copied-blue1' (find-copied-shape frame-blue' page' (:id frame-red'))]

           ;; ==== Check
           (t/is (= (:parent-id copied-blue1') (:id frame-red')))))))))

(t/deftest copy-shape-to-component
  "Coping a rect into a component results in a copy of the rect inside the component"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (ctho/add-frame :frame-blue {:name "frame-blue"}))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          frame-red  (cths/get-shape file :frame-red)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               frame-red'    (cths/get-shape file' :frame-red)
               frame-blue'   (cths/get-shape file' :frame-blue)
               copied-blue1' (find-copied-shape frame-blue' page' (:id frame-red'))]

           ;; ==== Check
           (t/is (= (:parent-id copied-blue1') (:id frame-red')))))))))

(t/deftest copy-component-to-frame
  "Coping a component c1 into a frame results in a copy of c1 inside the frame"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (ctho/add-frame :frame-blue {:name "frame-blue"})
                       (cthc/make-component :blue :frame-blue))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          frame-red  (cths/get-shape file :frame-red)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               frame-red'    (cths/get-shape file' :frame-red)
               frame-blue'   (cths/get-shape file' :frame-blue)
               copied-blue1' (find-copied-shape frame-blue' page' (:id frame-red'))]

           ;; ==== Check
           (t/is (= (:parent-id copied-blue1') (:id frame-red')))))))))

(t/deftest copy-component-to-component
  "Coping a component c1 into the main of a component c2 results in a copy of c1 inside c2"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (ctho/add-frame :frame-blue {:name "frame-blue"})
                       (cthc/make-component :blue :frame-blue))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          frame-red  (cths/get-shape file :frame-red)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               frame-red'    (cths/get-shape file' :frame-red)
               frame-blue'   (cths/get-shape file' :frame-blue)
               copied-blue1' (find-copied-shape frame-blue' page' (:id frame-red'))]

           ;; ==== Check
           (t/is (not (ctk/main-instance? copied-blue1')))
           (t/is (= (:parent-id copied-blue1') (:id frame-red')))))))))

(t/deftest cut-paste-component-to-component
  "Cutting a component c1 and pasting it into the main of a component c2 results in a restored main of C1 on root,
   because its not allowed to have a main inside a main"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (ctho/add-frame :frame-blue {:name "frame-blue"})
                       (cthc/make-component :blue :frame-blue))
          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          frame-red    (cths/get-shape file :frame-red)
          frame-blue   (cths/get-shape file :frame-blue)
          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id frame-blue))
           (dw/delete-selected)
           (dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'         (ths/get-file-from-state new-state)
               page'         (cthf/current-page file')
               frame-red'    (cths/get-shape file' :frame-red)
               frame-blue'   (cths/get-shape file' :frame-blue)
               copied-blue1' (find-copied-shape frame-blue' page' (:id frame-red'))
               copied-blue2' (find-copied-shape frame-blue' page' uuid/zero true)]

           ;; ==== Check
           (t/is (nil? copied-blue1'))
           (t/is (ctk/main-instance? copied-blue2'))
           (t/is (= (:parent-id copied-blue2') uuid/zero))))))))

(t/deftest copy-variant-container-into-component
  "Coping a variant container into the main of a component results in a new variant-container on root,
   because its not allowed to have a variant container inside a main"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          root         (cths/get-shape-by-id file uuid/zero)
          frame-red    (cths/get-shape file :frame-red)
          v01          (cths/get-shape file :v01)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id v01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               root'          (cths/get-shape-by-id file' uuid/zero)
               frame-red'     (cths/get-shape file' :frame-red)
               root-children' (->> (:shapes root')
                                   (map #(cths/get-shape-by-id file' %)))]

           ;; ==== Check
           ;; The main shape of the component have no children
           (t/is (= 0 (count (:shapes frame-red'))))
           ;; Root had two children, now have 3
           (t/is (= 2 (count (:shapes root))))
           (t/is (= 3 (count (:shapes root'))))
           ;; Two of the children of root are variant-containers
           (t/is (= 2 (count (filter ctk/is-variant-container? root-children'))))))))))

(t/deftest cut-paste-variant-container-into-component
  "Cuting and pasting a variant container into the main of a component results in a new variant-container on root,
   because its not allowed to have a variant container inside a main"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          root         (cths/get-shape-by-id file uuid/zero)
          frame-red    (cths/get-shape file :frame-red)
          v01          (cths/get-shape file :v01)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id v01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id v01))
           (dw/delete-selected)
           (dws/select-shape (:id frame-red))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               root'          (cths/get-shape-by-id file' uuid/zero)
               frame-red'     (cths/get-shape file' :frame-red)
               root-children' (->> (:shapes root')
                                   (map #(cths/get-shape-by-id file' %)))]

           ;; ==== Check
           ;; The main shape of the component have no children
           (t/is (= 0 (count (:shapes frame-red'))))
           ;; Root had two children, now it still have two (because we have cutted one of them, and then created a new one)
           (t/is (= 2 (count (:shapes root))))
           (t/is (= 2 (count (:shapes root'))))
           ;; One of the children of root is a variant-container
           (t/is (= 1 (count (filter ctk/is-variant-container? root-children'))))))))))

(t/deftest copy-variant-into-different-variant-container
  "Coping a variant into a different variant-container creates a new variant inside that container"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (thv/add-variant :v02 :c03 :m03 :c04 :m04))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          m01          (cths/get-shape file :m01)
          v02          (cths/get-shape file :v02)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id m01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id v02))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v02'           (cths/get-shape file' :v02)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v02 had two children, now it have 3
           (t/is (= 2 (count (:shapes v02))))
           (t/is (= 3 (count (:shapes v02'))))

           ;;There was 4 components, now there are 5
           (t/is (= 4 (count components)))
           (t/is (= 5 (count components')))))))))

(t/deftest copy-variant-into-variant-another-container
  "Coping a variant into a variant of a different variant-container creates a copy of the variant inside"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02)
                       (thv/add-variant :v02 :c03 :m03 :c04 :m04))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          m01          (cths/get-shape file :m01)
          v02          (cths/get-shape file :v02)
          m03          (cths/get-shape file :m03)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id m01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id m03))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v02'           (cths/get-shape file' :v02)
               m03'           (cths/get-shape file' :m03)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v02 had two children, now it have still 2
           (t/is (= 2 (count (:shapes v02))))
           (t/is (= 2 (count (:shapes v02'))))

           ;; m03 had no children, now it have 1
           (t/is (= 0 (count (:shapes m03))))
           (t/is (= 1 (count (:shapes m03'))))

           ;;There was 4 components, now there is still 4
           (t/is (= 4 (count components)))
           (t/is (= 4 (count components')))))))))

(t/deftest copy-variant-into-its-variant-container
  "Coping a variant into its own variant-container creates a new variant inside that container"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          m01          (cths/get-shape file :m01)
          v01          (cths/get-shape file :v01)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id m01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id v01))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v01'           (cths/get-shape file' :v01)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v01 had two children, now it have 3
           (t/is (= 2 (count (:shapes v01))))
           (t/is (= 3 (count (:shapes v01'))))

           ;;There was 2 components, now there are 3
           (t/is (= 2 (count components)))
           (t/is (= 3 (count components')))))))))

(t/deftest copy-variant-into-itself
  "Coping a variant into itself creates a new variant inside its container"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          m01          (cths/get-shape file :m01)
          v01          (cths/get-shape file :v01)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id m01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id m01))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v01'           (cths/get-shape file' :v01)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v01 had two children, now it have 3
           (t/is (= 2 (count (:shapes v01))))
           (t/is (= 3 (count (:shapes v01'))))

           ;;There was 2 components, now there are 3
           (t/is (= 2 (count components)))
           (t/is (= 3 (count components')))))))))

(t/deftest copy-variant-into-a-brother
  "Coping a variant into a brother variant creates a new variant inside its container"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          m01          (cths/get-shape file :m01)
          m02          (cths/get-shape file :m02)
          v01          (cths/get-shape file :v01)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id m01)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id m02))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v01'           (cths/get-shape file' :v01)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v01 had two children, now it have 3
           (t/is (= 2 (count (:shapes v01))))
           (t/is (= 3 (count (:shapes v01'))))

           ;;There was 2 components, now there are 3
           (t/is (= 2 (count components)))
           (t/is (= 3 (count components')))))))))

(t/deftest copy-component-into-a-variant-container
  "Coping a component into a variant-container creates a new variant inside that container"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          frame-red    (cths/get-shape file :frame-red)
          v01          (cths/get-shape file :v01)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id frame-red)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id v01))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v01'           (cths/get-shape file' :v01)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v01 had two children, now it have 3
           (t/is (= 2 (count (:shapes v01))))
           (t/is (= 3 (count (:shapes v01'))))

           ;;There was 3 components, now there are 4
           (t/is (= 3 (count components)))
           (t/is (= 4 (count components')))))))))

(t/deftest copy-component-into-a-variant
  "Coping a component into a variant creates a copy of the component inside the variant"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (cthc/make-component :red :frame-red)
                       (thv/add-variant :v01 :c01 :m01 :c02 :m02))

          store    (ths/setup-store file)

          ;; ==== Action
          page         (cthf/current-page file)
          frame-red    (cths/get-shape file :frame-red)
          v01          (cths/get-shape file :v01)
          m01          (cths/get-shape file :m01)
          components   (cthc/get-components file)

          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id frame-red)} (:objects page) {(:id  file) file} page file features version)

          events
          [(dws/select-shape (:id m01))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'          (ths/get-file-from-state new-state)
               v01'           (cths/get-shape file' :v01)
               m01'           (cths/get-shape file' :m01)
               components'    (cthc/get-components file')]

           ;; ==== Check
           ;; v01 had two children, now it have still 2
           (t/is (= 2 (count (:shapes v01))))
           (t/is (= 2 (count (:shapes v01'))))

           ;; m01 had no children, now it have 1
           (t/is (= 0 (count (:shapes m01))))
           (t/is (= 1 (count (:shapes m01'))))

           ;;There was 3 components, now there are still 3
           (t/is (= 3 (count components)))
           (t/is (= 3 (count components')))))))))

(t/deftest paste-with-unloaded-page-is-noop
  "Pasting while the page is not loaded (e.g. right after opening the workspace) is ignored instead of crashing"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-blue {:name "frame-blue"}))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          page-id    (cthf/current-page-id file)
          file-id    (:id file)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          drop-page  (ptk/reify ::drop-current-page
                       ptk/UpdateEvent
                       (update [_ state]
                         (update-in state [:files file-id :data :pages-index] dissoc page-id)))

          events
          [drop-page
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file' (ths/get-file-from-state new-state)
               page' (cthf/current-page file')]

           ;; ==== Check
           ;; The page is still not loaded and nothing was pasted anywhere
           (t/is (some? file'))
           (t/is (nil? page'))))))))

(t/deftest paste-with-detached-selection-pastes-by-position
  "Pasting with a selection of shapes detached from the shape tree falls back to pasting at the pointer position"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red"})
                       (ctho/add-frame :frame-blue {:name "frame-blue"}))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          page-id    (cthf/current-page-id file)
          file-id    (:id file)
          frame-red  (cths/get-shape file :frame-red)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          ;; Detach the selected shape from the tree (it stays in the
          ;; objects map but is no longer reachable from the root)
          detach-and-select (ptk/reify ::detach-and-select
                              ptk/UpdateEvent
                              (update [_ state]
                                (-> state
                                    (update-in [:files file-id :data :pages-index page-id :objects uuid/zero :shapes]
                                               (fn [shapes] (vec (remove #(= % (:id frame-red)) shapes))))
                                    (assoc-in [:workspace-local :selected] #{(:id frame-red)}))))

          _          (rx/push! ms/mouse-position (gpt/point 1000 1000))

          events
          [detach-and-select
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (rx/push! ms/mouse-position nil)
         (let [;; ==== Get
               file'       (ths/get-file-from-state new-state)
               page'       (cthf/current-page file')
               frame-blue' (cths/get-shape file' :frame-blue)
               copied'     (find-copied-shape frame-blue' page' uuid/zero)]

           ;; ==== Check
           ;; The copy lands at the pointer position on the root
           (t/is (some? copied'))
           (t/is (= 1000 (:x copied')))
           (t/is (= 1000 (:y copied')))))))))

(t/deftest paste-with-root-plus-orphan-selection-pastes-by-position
  "Pasting with the root plus a shape orphaned from the objects map falls back to pasting at the pointer position"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-blue {:name "frame-blue" :x 0 :y 0 :width 500 :height 500})
                       (ctho/add-rect :rect1))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          page-id    (cthf/current-page-id file)
          file-id    (:id file)
          rect1      (cths/get-shape file :rect1)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          ;; Orphan the shape (parent missing from the objects map and
          ;; absent from the tree) and select it together with the root.
          ;; clean-loops cannot prune either id, so there is no base
          ;; shape and the frame branches cannot handle the selection.
          detach-and-select (ptk/reify ::detach-and-select-root
                              ptk/UpdateEvent
                              (update [_ state]
                                (-> state
                                    (update-in [:files file-id :data :pages-index page-id :objects]
                                               (fn [objects]
                                                 (-> objects
                                                     (update-in [uuid/zero :shapes]
                                                                (fn [shapes]
                                                                  (vec (remove #(= % (:id rect1)) shapes))))
                                                     (update (:id rect1) assoc :parent-id (uuid/custom 9 9)))))
                                    (assoc-in [:workspace-local :selected] #{uuid/zero (:id rect1)}))))

          _          (rx/push! ms/mouse-position (gpt/point 1000 1000))

          events
          [detach-and-select
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (rx/push! ms/mouse-position nil)
         (let [;; ==== Get
               file'       (ths/get-file-from-state new-state)
               page'       (cthf/current-page file')
               frame-blue' (cths/get-shape file' :frame-blue)
               copied'     (find-copied-shape frame-blue' page' uuid/zero)]

           ;; ==== Check
           ;; The copy lands at the pointer position on the root
           (t/is (some? copied'))
           (t/is (= 1000 (:x copied')))
           (t/is (= 1000 (:y copied')))))))))

(t/deftest paste-with-root-only-selection-stays-on-frame-path
  "Pasting with only the root selected keeps using the frame branches, not the pointer fallback"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-blue {:name "frame-blue" :x 0 :y 0 :width 500 :height 500}))
          store    (ths/setup-store file)

          ;; ==== Action
          page       (cthf/current-page file)
          frame-blue (cths/get-shape file :frame-blue)
          features   #{}
          version    67

          pdata      (thp/simulate-copy-shape #{(:id frame-blue)} (:objects page) {(:id  file) file} page file features version)

          select-root (ptk/reify ::select-root
                        ptk/UpdateEvent
                        (update [_ state]
                          (assoc-in state [:workspace-local :selected] #{uuid/zero})))

          ;; Push the pointer far away so a pointer fallback would be
          ;; observable: the frame path must not land the copy here.
          ;; (A naive plain `(nil? base)` fallback condition would.)
          _          (rx/push! ms/mouse-position (gpt/point 1000 1000))

          events
          [select-root
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (rx/push! ms/mouse-position nil)
         (let [;; ==== Get
               file'       (ths/get-file-from-state new-state)
               page'       (cthf/current-page file')
               frame-blue' (cths/get-shape file' :frame-blue)
               copied'     (find-copied-shape frame-blue' page' uuid/zero)]

           ;; ==== Check
           ;; The copy lands under the root through the frame path,
           ;; away from the pointer position.
           (t/is (some? copied'))
           (t/is (= uuid/zero (:parent-id copied')))
           (t/is (not (= 1000 (:x copied'))))))))))

(t/deftest create-shape-with-detached-selection-uses-cursor-frame
  "Creating a shape with a selection detached from the shape tree falls back to the frame under the cursor"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red" :x 1000 :y 1000 :width 100 :height 100})
                       (ctho/add-frame :frame-blue {:name "frame-blue" :x 0 :y 0 :width 500 :height 500})
                       (ctho/add-rect :rect1))
          store    (ths/setup-store file)

          ;; ==== Action
          page-id    (cthf/current-page-id file)
          file-id    (:id file)
          rect1      (cths/get-shape file :rect1)
          frame-blue (cths/get-shape file :frame-blue)

          ;; Detach the selected shape from the tree (it stays in the
          ;; objects map but is no longer reachable from the root)
          detach-and-select (ptk/reify ::detach-and-select
                              ptk/UpdateEvent
                              (update [_ state]
                                (-> state
                                    (update-in [:files file-id :data :pages-index page-id :objects uuid/zero :shapes]
                                               (fn [shapes] (vec (remove #(= % (:id rect1)) shapes))))
                                    (assoc-in [:workspace-local :selected] #{(:id rect1)}))))

          events
          [detach-and-select
           (dwsh/create-and-add-shape :rect 100 100 {:name "detached-rect"
                                                     :width 50 :height 50
                                                     :x 100 :y 100})]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'  (ths/get-file-from-state new-state)
               page'  (cthf/current-page file')
               created' (->> (vals (:objects page'))
                             (filter #(= (:name %) "detached-rect"))
                             first)]

           ;; ==== Check
           ;; The shape is created inside the frame under the cursor
           (t/is (some? created'))
           (t/is (= (:id frame-blue) (:parent-id created')))))))))

(t/deftest svg-upload-with-detached-selection-uses-cursor-frame
  "Uploading an SVG with a selection detached from the shape tree falls back to the frame under the cursor"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-frame :frame-red {:name "frame-red" :x 1000 :y 1000 :width 100 :height 100})
                       (ctho/add-frame :frame-blue {:name "frame-blue" :x 0 :y 0 :width 500 :height 500})
                       (ctho/add-rect :rect1))
          store    (ths/setup-store file)

          ;; ==== Action
          page-id    (cthf/current-page-id file)
          file-id    (:id file)
          rect1      (cths/get-shape file :rect1)
          frame-blue (cths/get-shape file :frame-blue)

          ;; Detach the selected shape from the tree (it stays in the
          ;; objects map but is no longer reachable from the root)
          detach-and-select (ptk/reify ::detach-and-select-svg
                              ptk/UpdateEvent
                              (update [_ state]
                                (-> state
                                    (update-in [:files file-id :data :pages-index page-id :objects uuid/zero :shapes]
                                               (fn [shapes] (vec (remove #(= % (:id rect1)) shapes))))
                                    (assoc-in [:workspace-local :selected] #{(:id rect1)}))))

          svg-data   {:name "test.svg"
                      :attrs {:width 100 :height 100}
                      :content [{:tag :rect
                                 :attrs {:x "10" :y "10" :width "20" :height "20"}}]}

          events
          [detach-and-select
           (dwsvg/add-svg-shapes nil svg-data (gpt/point 100 100) nil)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'  (ths/get-file-from-state new-state)
               page'  (cthf/current-page file')
               created' (->> (vals (:objects page'))
                             (filter #(= (:name %) "test"))
                             first)]

           ;; ==== Check
           ;; The shape is created inside the frame under the cursor
           (t/is (some? created'))
           (t/is (= (:id frame-blue) (:parent-id created')))))))))

(t/deftest props-paste-with-unloaded-page-is-noop
  "Pasting props while the page is not loaded is ignored instead of crashing"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-rect :rect1))
          store    (ths/setup-store file)

          ;; ==== Action
          page-id    (cthf/current-page-id file)
          file-id    (:id file)
          rect1      (cths/get-shape file :rect1)

          props      {:fills (cths/sample-fills-color :fill-color "#ff0000")}
          payload    (transit/encode-str {:type :copied-props
                                          :features #{"components/v2"}
                                          :version 67
                                          :props props
                                          :images []})
          _          (install-read-clipboard! payload)

          select-rect (ptk/reify ::select-rect-props
                        ptk/UpdateEvent
                        (update [_ state]
                          (assoc-in state [:workspace-local :selected] #{(:id rect1)})))

          drop-page  (ptk/reify ::drop-current-page-props
                       ptk/UpdateEvent
                       (update [_ state]
                         (update-in state [:files file-id :data :pages-index] dissoc page-id)))

          ;; The clipboard read resolves asynchronously, after run-store
          ;; emits its events; settle the run on a timer so the paste
          ;; pipeline (or its absence) has completed either way.
          _          (js/setTimeout #(ptk/emit! store (ptk/data-event ::props-probe-done)) 250)
          stopper    (fn [stream] (rx/filter (ptk/type? ::props-probe-done) stream))

          events
          [select-rect
           drop-page
           (dw/paste-selected-props)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file' (ths/get-file-from-state new-state)
               page' (cthf/current-page file')]

           ;; ==== Check
           ;; The page is still not loaded and nothing was pasted anywhere
           (t/is (some? file'))
           (t/is (nil? page'))))
       stopper))))

(t/deftest props-paste-applies-props-on-loaded-page
  "Pasting props on a loaded page applies them to the selection"
  (t/async
    done
    (let [;; ==== Setup
          file     (-> (cthf/sample-file :file1)
                       (ctho/add-rect :rect1))
          store    (ths/setup-store file)

          ;; ==== Action
          rect1      (cths/get-shape file :rect1)

          props      {:fills (cths/sample-fills-color :fill-color "#ff0000")}
          payload    (transit/encode-str {:type :copied-props
                                          :features #{"components/v2"}
                                          :version 67
                                          :props props
                                          :images []})
          _          (install-read-clipboard! payload)

          select-rect (ptk/reify ::select-rect-props-ok
                        ptk/UpdateEvent
                        (update [_ state]
                          (assoc-in state [:workspace-local :selected] #{(:id rect1)})))

          _          (js/setTimeout #(ptk/emit! store (ptk/data-event ::props-probe-ok)) 250)
          stopper    (fn [stream] (rx/filter (ptk/type? ::props-probe-ok) stream))

          events
          [select-rect
           (dw/paste-selected-props)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'  (ths/get-file-from-state new-state)
               rect1' (cths/get-shape file' :rect1)]

           ;; ==== Check
           (t/is (= "#ff0000" (-> rect1' :fills first :fill-color)))))
       stopper))))