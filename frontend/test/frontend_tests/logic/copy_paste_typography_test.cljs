;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.copy-paste-typography-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.text :as txt]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.selection :as dws]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn- setup-library-file []
  (-> (cthf/sample-file :library-file)
      (cths/add-sample-typography :typography-1 :name "Heading")))

(defn- setup-main-file [library-file]
  (let [content (txt/change-text nil "Hello world"
                                 :typography-ref-id (cthi/id :typography-1)
                                 :typography-ref-file (:id library-file))]
    (-> (cthf/sample-file :main-file)
        (ctho/add-frame :frame-1 :name "frame-1")
        (cths/add-sample-shape :text-1 :type :text :parent-label :frame-1 :content content))))

(defn- get-typography-ref
  [shape]
  (first (txt/node-seq #(some? (:typography-ref-id %)) (:content shape))))

(t/deftest copy-paste-text-keeps-external-typography-ref
  "Copying and pasting a text shape retains the association with a text
  style defined in an external library"
  (t/async
    done
    (let [;; ==== Setup
          library-file (setup-library-file)
          file         (setup-main-file library-file)
          store        (ths/setup-store file {:libraries [library-file]})

          ;; ==== Action
          page         (cthf/current-page file)
          frame-1      (cths/get-shape file :frame-1)
          text-1       (cths/get-shape file :text-1)
          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id text-1)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape (:id frame-1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'     (ths/get-file-from-state new-state)
               frame-1'  (cths/get-shape file' :frame-1)
               pasted-id (->> (:shapes frame-1')
                              (remove #(= % (:id text-1)))
                              first)
               pasted    (cths/get-shape-by-id file' pasted-id)
               ref-node  (get-typography-ref pasted)]

           ;; ==== Check
           (t/is (some? pasted))
           (t/is (some? ref-node))
           (t/is (= (:typography-ref-id ref-node) (cthi/id :typography-1)))
           (t/is (= (:typography-ref-file ref-node) (:id library-file)))))))))

(t/deftest copy-paste-text-drops-typography-ref-from-unlinked-file
  "Copying and pasting a text shape removes typography references that
  point to a file which isn't the current file nor a linked library"
  (t/async
    done
    (let [;; ==== Setup
          unrelated-file (setup-library-file)
          library-file   (-> (cthf/sample-file :library-file-2)
                             (cths/add-sample-typography :typography-2 :name "Body"))
          file           (setup-main-file unrelated-file)
          store          (ths/setup-store file {:libraries [library-file]})

          ;; ==== Action
          page         (cthf/current-page file)
          frame-1      (cths/get-shape file :frame-1)
          text-1       (cths/get-shape file :text-1)
          features     #{}
          version      67

          pdata        (thp/simulate-copy-shape #{(:id text-1)} (:objects page) {(:id file) file} page file features version)

          events
          [(dws/select-shape (:id frame-1))
           (dw/paste-shapes pdata)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [;; ==== Get
               file'     (ths/get-file-from-state new-state)
               frame-1'  (cths/get-shape file' :frame-1)
               pasted-id (->> (:shapes frame-1')
                              (remove #(= % (:id text-1)))
                              first)
               pasted    (cths/get-shape-by-id file' pasted-id)
               ref-node  (get-typography-ref pasted)]

           ;; ==== Check
           (t/is (some? pasted))
           (t/is (nil? ref-node))))))))
