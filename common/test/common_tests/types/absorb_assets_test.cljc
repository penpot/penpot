;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.absorb-assets-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.text :as txt]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.typographies-list :as ctyl]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest absorb-components
  (let [;; Setup
        library (-> (thf/sample-file :library :is-shared true)
                    (tho/add-simple-component :component1 :main-root :rect1))

        file (-> (thf/sample-file :file)
                 (thc/instantiate-component :component1 :copy-root :library library))

        ;; Action
        file' (ctf/update-file-data
               file
               #(ctf/absorb-assets % (:data library)))

        _ (thf/validate-file! file')

        ;; Get
        pages'      (ctpl/pages-seq (ctf/file-data file'))
        components' (ctkl/components-seq (ctf/file-data file'))
        component' (first components')

        copy-root' (ths/get-shape file' :copy-root)
        main-root' (ctf/get-ref-shape (ctf/file-data file') component' copy-root')]

    ;; Check
    (t/is (= (count pages') 2))
    (t/is (= (:name (first pages')) "Page 1"))
    (t/is (= (:name (second pages')) "Main components"))

    (t/is (= (count components') 1))

    (t/is (ctk/instance-of? copy-root' (:id file') (:id component')))
    (t/is (ctk/is-main-of? main-root' copy-root'))
    (t/is (ctk/main-instance-of? (:id main-root') (:id (second pages')) component'))))

(t/deftest absorb-colors
  (let [;; Setup
        library (-> (thf/sample-file :library :is-shared true)
                    (ths/add-sample-library-color :color1 {:name "Test color"
                                                           :color "#abcdef"}))

        file    (-> (thf/sample-file :file)
                    (ths/add-sample-shape :shape1
                                          :type :rect
                                          :name "Rect1"
                                          :fills [{:fill-color "#abcdef"
                                                   :fill-opacity 1
                                                   :fill-color-ref-id (thi/id :color1)
                                                   :fill-color-ref-file (thi/id :library)}]))

        ;; Action
        file' (ctf/update-file-data
               file
               #(ctf/absorb-assets % (:data library)))

        _ (thf/validate-file! file')

        ;; Get
        colors' (ctc/colors-seq (ctf/file-data file'))
        shape1' (ths/get-shape file' :shape1)
        fill'   (first (:fills shape1'))]

    ;; Check
    (t/is (= (count colors') 1))
    (t/is (= (:id (first colors')) (thi/id :color1)))
    (t/is (= (:name (first colors')) "Test color"))
    (t/is (= (:color (first colors')) "#abcdef"))

    (t/is (= (:fill-color fill') "#abcdef"))
    (t/is (= (:fill-color-ref-id fill') (thi/id :color1)))
    (t/is (= (:fill-color-ref-file fill') (:id file')))))

(t/deftest absorb-typographies
  (let [;; Setup
        library (-> (thf/sample-file :library :is-shared true)
                    (ths/add-sample-typography :typography1 {:name "Test typography"}))

        file    (-> (thf/sample-file :file)
                    (ths/add-sample-shape :shape1
                                          :type :text
                                          :name "Text1"
                                          :content {:type "root"
                                                    :children [{:type "paragraph-set"
                                                                :children [{:type "paragraph"
                                                                            :key "67uep"
                                                                            :children [{:text "Example text"
                                                                                        :typography-ref-id (thi/id :typography1)
                                                                                        :typography-ref-file (thi/id :library)
                                                                                        :line-height "1.2"
                                                                                        :font-style "normal"
                                                                                        :text-transform "none"
                                                                                        :text-align "left"
                                                                                        :font-id "sourcesanspro"
                                                                                        :font-family "sourcesanspro"
                                                                                        :font-size "14"
                                                                                        :font-weight "400"
                                                                                        :font-variant-id "regular"
                                                                                        :text-decoration "none"
                                                                                        :letter-spacing "0"
                                                                                        :fills [{:fill-color "#000000"
                                                                                                 :fill-opacity 1}]}]}]}]}))
        ;; Action
        file' (ctf/update-file-data
               file
               #(ctf/absorb-assets % (:data library)))

        _ (thf/validate-file! file')

        ;; Get
        typographies' (ctyl/typographies-seq (ctf/file-data file'))
        shape1'       (ths/get-shape file' :shape1)
        text-node'    (d/seek #(some? (:text %)) (txt/node-seq (:content shape1')))]

    ;; Check
    (t/is (= (count typographies') 1))
    (t/is (= (:id (first typographies')) (thi/id :typography1)))
    (t/is (= (:name (first typographies')) "Test typography"))

    (t/is (= (:typography-ref-id text-node') (thi/id :typography1)))
    (t/is (= (:typography-ref-file text-node') (:id file')))))
