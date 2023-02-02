;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types-file-test
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.text :as txt]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as ctyl]
   [app.common.uuid :as uuid]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as t]
   [common-tests.helpers.components :as thk]
   [common-tests.helpers.files :as thf]
   [cuerdas.core :as str]))

(t/use-fixtures :each thf/reset-idmap!)

#_(t/deftest test-absorb-components
  (let [library-id      (uuid/custom 1 1)
        library-page-id (uuid/custom 2 2)
        file-id         (uuid/custom 3 3)
        file-page-id    (uuid/custom 4 4)

        library (-> (thf/sample-file library-id library-page-id {:is-shared true})
                    (thf/sample-shape :group1
                                      :group
                                      library-page-id
                                      {:name "Group1"})
                    (thf/sample-shape :shape1
                                      :rect
                                      library-page-id
                                      {:name "Rect1"
                                       :parent-id (thf/id :group1)})
                    (thf/sample-component :component1
                                          library-page-id
                                          (thf/id :group1)))

        file (-> (thf/sample-file file-id file-page-id)
                 (thf/sample-instance :instance1
                                      file-page-id
                                      library
                                      (thf/id :component1)))

        absorbed-file (ctf/update-file-data
                        file
                        #(ctf/absorb-assets % (:data library)))

        pages      (ctpl/pages-seq (ctf/file-data absorbed-file))
        components (ctkl/components-seq (ctf/file-data absorbed-file))
        shapes-1   (ctn/shapes-seq (first pages))
        shapes-2   (ctn/shapes-seq (second pages))

        [[p-group p-shape] [c-group1 c-shape1] component1]
        (thk/resolve-instance-and-main
          (first pages)
          (:id (second shapes-1))
          {file-id absorbed-file})

        [[lp-group lp-shape] [c-group2 c-shape2] component2]
        (thk/resolve-instance-and-main
          (second pages)
          (:id (second shapes-2))
          {file-id absorbed-file})]

    ;; Uncomment to debug

    ;; (println "\n===== library")
    ;; (ctf/dump-tree (:data library)
    ;;                library-page-id
    ;;                {}
    ;;                true)

    ;; (println "\n===== file")
    ;; (ctf/dump-tree (:data file)
    ;;                file-page-id
    ;;                {library-id library}
    ;;                true)

    ;; (println "\n===== absorbed file")
    ;; (println (str "\n<" (:name (first pages)) ">"))
    ;; (ctf/dump-tree (:data absorbed-file)
    ;;                (:id (first pages))
    ;;                {file-id absorbed-file}
    ;;                false)
    ;; (println (str "\n<" (:name (second pages)) ">"))
    ;; (ctf/dump-tree (:data absorbed-file)
    ;;                (:id (second pages))
    ;;                {file-id absorbed-file}
    ;;                false)

    (t/is (= (count pages) 2))
    (t/is (= (:name (first pages)) "Page 1"))
    (t/is (= (:name (second pages)) "Library backup"))

    (t/is (= (count components) 1))

    (t/is (= (:name p-group) "Group1"))
    (t/is (ctk/instance-of? p-group file-id (:id component1)))
    (t/is (not (:main-instance? p-group)))
    (t/is (not (ctk/is-main-instance? (:id p-group) file-page-id component1)))
    (t/is (ctk/is-main-of? c-group1 p-group))

    (t/is (= (:name p-shape) "Rect1"))
    (t/is (ctk/is-main-of? c-shape1 p-shape))))


(t/deftest test-absorb-colors
  (let [library-id      (uuid/custom 1 1)
        library-page-id (uuid/custom 2 2)
        file-id         (uuid/custom 3 3)
        file-page-id    (uuid/custom 4 4)

        library (-> (thf/sample-file library-id library-page-id {:is-shared true})
                    (thf/sample-color :color1 {:name "Test color"
                                               :color "#abcdef"}))

        file (-> (thf/sample-file file-id file-page-id)
                 (thf/sample-shape :shape1
                                   :rect
                                   file-page-id
                                   {:name "Rect1"
                                    :fills [{:fill-color "#abcdef"
                                             :fill-opacity 1
                                             :fill-color-ref-id (thf/id :color1)
                                             :fill-color-ref-file library-id}]}))

        absorbed-file (ctf/update-file-data
                        file
                        #(ctf/absorb-assets % (:data library)))

        colors (ctcl/colors-seq (ctf/file-data absorbed-file))
        page   (ctpl/get-page (ctf/file-data absorbed-file) file-page-id)
        shape1 (ctn/get-shape page (thf/id :shape1))
        fill   (first (:fills shape1))]

    (t/is (= (count colors) 1))
    (t/is (= (:id (first colors)) (thf/id :color1)))
    (t/is (= (:name (first colors)) "Test color"))
    (t/is (= (:color (first colors)) "#abcdef"))

    (t/is (= (:fill-color fill) "#abcdef"))
    (t/is (= (:fill-color-ref-id fill) (thf/id :color1)))
    (t/is (= (:fill-color-ref-file fill) file-id))))

(t/deftest test-absorb-typographies
  (let [library-id      (uuid/custom 1 1)
        library-page-id (uuid/custom 2 2)
        file-id         (uuid/custom 3 3)
        file-page-id    (uuid/custom 4 4)

        library (-> (thf/sample-file library-id library-page-id {:is-shared true})
                    (thf/sample-typography :typography1 {:name "Test typography"}))

        file (-> (thf/sample-file file-id file-page-id)
                 (thf/sample-shape :shape1
                                   :text
                                   file-page-id
                                   {:name "Text1"
                                    :content {:type "root"
                                              :children [{:type "paragraph-set"
                                                          :children [{:type "paragraph"
                                                                      :key "67uep"
                                                                      :children [{:text "Example text"
                                                                                  :typography-ref-id (thf/id :typography1)
                                                                                  :typography-ref-file library-id
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
                                                                                           :fill-opacity 1}]}]
                                                                      }]}]}}))
        absorbed-file (ctf/update-file-data
                        file
                        #(ctf/absorb-assets % (:data library)))

        typographies (ctyl/typographies-seq (ctf/file-data absorbed-file))
        page         (ctpl/get-page (ctf/file-data absorbed-file) file-page-id)
        shape1       (ctn/get-shape page (thf/id :shape1))
        text-node    (d/seek #(some? (:text %)) (txt/node-seq (:content shape1)))]

    (t/is (= (count typographies) 1))
    (t/is (= (:id (first typographies)) (thf/id :typography1)))
    (t/is (= (:name (first typographies)) "Test typography"))

    (t/is (= (:typography-ref-id text-node) (thf/id :typography1)))
    (t/is (= (:typography-ref-file text-node) file-id))))

