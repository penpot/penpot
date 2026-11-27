;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-export-images-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.types.shape :as cts]
   [app.main.render :as render]
   [app.main.ui.shapes.fills :as fills]
   [cljs.test :refer [deftest is testing]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def image-a
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :name "image-a"})

(def image-b
  {:id #uuid "00000000-0000-0000-0000-000000000002"
   :name "image-b"})

(deftest collects-image-data
  (let [nested [{:fill-color "#ffffff"} {:fill-image image-a} {:fill-image image-b}]]
    (doseq [[description shape expected]
            [["image metadata and nested images"
              {:type :image :metadata image-b :fills nested} [image-b image-a image-b]]
             ["missing image metadata" {:type :image :fills nested} [nil image-a image-b]]
             ["top-level and nested images" {:type :rect :fill-image image-b :fills nested} [image-b image-a image-b]]
             ["nested images" {:type :rect :fills nested} [image-a image-b]]
             ["empty fills" {:type :rect :fills []} []]
             ["absent fills" {:type :rect} []]
             ["image metadata takes precedence" {:type :image :metadata image-a :fill-image image-b :fills nested} [image-a image-a image-b]]]]
      (testing description
        (is (= expected (#'render/get-image-data shape)))))))

(defn- render-fills
  [attributes]
  (let [shape (merge (cts/setup-shape {:type :rect :x 0 :y 0 :width 100 :height 200})
                     attributes)]
    (rds/renderToStaticMarkup
     (mf/element fills/internal-fills #js {:shape shape :render-id "test"}))))

(defn- tags
  [markup tag]
  (re-seq (re-pattern (str "<" tag "[^>]*>")) markup))

(deftest nested-image-fill-markup
  (let [uri "data:image/png;base64,bmVzdGVk"
        markup (render-fills {:fills [{:fill-image (assoc image-a :data-uri uri)
                                       :fill-opacity 1}]})
        pattern (first (tags markup "pattern"))
        images (tags markup "image")]
    (is (str/includes? pattern "width=\"105\""))
    (is (str/includes? pattern "height=\"210\""))
    (is (= 1 (count images)))
    (is (str/includes? (first images) (str "href=\"" uri "\"")))))

(deftest legacy-and-nested-image-fill-markup
  (let [legacy-uri "data:image/png;base64,bGVnYWN5"
        nested-uri "data:image/png;base64,bmVzdGVk"
        markup (render-fills {:fill-image (assoc image-a :data-uri legacy-uri)
                              :fills [{:fill-image (assoc image-b :data-uri nested-uri)
                                       :fill-opacity 1}]})
        images (tags markup "image")]
    (is (= 2 (count images)))
    (is (str/includes? (first images) (str "href=\"" nested-uri "\"")))
    (is (str/includes? (second images) (str "href=\"" legacy-uri "\"")))
    (is (some #(and (str/includes? % "width=\"105\"")
                    (str/includes? % "height=\"210\"")
                    (str/includes? % "fill=\"none\""))
              (tags markup "rect")))))
