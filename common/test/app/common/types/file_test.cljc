;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.file-test
  (:require
   [clojure.test :as t]
   [app.common.geom.point :as gpt]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.common.test-helpers.files :as thf]

   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [cuerdas.core :as str]
   ))

(t/use-fixtures :each
  {:before thf/reset-idmap!})

(t/deftest test-absorb-assets
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
                        #(ctf/absorb-assets % (:data library)))]

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
    ;; (ctf/dump-tree (:data absorbed-file)
    ;;                file-page-id
    ;;                {}
    ;;                true)

    (t/is (= library-id (:id library)))
    (t/is (= file-id (:id absorbed-file)))))

