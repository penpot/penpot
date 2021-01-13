;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.svg-upload
  (:require
   [app.common.data :as d]
   [app.util.data :as ud]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]

   [app.util.geom.path :as ugp]))

(defn- svg-dimensions [data]
  (let [width (get-in data [:attrs :width] 100)
        height (get-in data [:attrs :height] 100)
        viewbox (get-in data [:attrs :viewBox] (str "0 0 " width " " height))
        [_ _ width-str height-str] (str/split viewbox " ")
        width (d/parse-integer width-str)
        height (d/parse-integer height-str)]
    [width height]))


(defn tag-name [{:keys [tag]}]
  (cond (string? tag) tag
        (keyword? tag) (name tag)
        (nil? tag) "node"
        :else (str tag)))

(defn setup-fill [shape attrs]
  (-> shape
      (assoc :fill-color (:fill attrs "#000000"))
      (assoc :fill-opacity (ud/parse-float (:fill-opacity attrs "1")))))

(defn setup-stroke [shape attrs]
  (-> shape
      (assoc :stroke-color (:stroke attrs "#000000"))
      (assoc :stroke-opacity (ud/parse-float (:stroke-opacity attrs 1)))
      (assoc :stroke-style :solid)
      (assoc :stroke-width (ud/parse-float (:stroke-width attrs "1")))
      (assoc :stroke-alignment :center)))

(defn add-style-attributes [shape {:keys [attrs]}]
  (cond-> shape
    (d/any-key? attrs :fill :fill-opacity)
    (setup-fill attrs)
    
    (d/any-key? attrs :stroke :stroke-width :stroke-opacity)
    (setup-stroke attrs)))

(defn create-raw-svg [name frame-id x y width height data]
  (-> {:id (uuid/next)
       :type :svg-raw
       :name name
       :frame-id frame-id
       :width width
       :height height
       :x x
       :y y
       :content data}
      (gsh/setup-selrect)))

(defn parse-path [name frame-id {:keys [attrs] :as data}]
  (let [content (ugp/path->content (:d attrs))
        selrect (gsh/content->selrect content)
        points (gsh/rect->points selrect)]
    (-> {:id (uuid/next)
         :type :path
         :name name
         :frame-id frame-id
         ;; :width width
         ;; :height height
         ;; :x x
         ;; :y y
         :content content
         :selrect selrect
         :points points}

        (add-style-attributes data))))

(defn parse-svg-element [root-shape data unames]
  (let [root-id (:id root-shape)
        frame-id (:frame-id root-shape)
        {:keys [x y width height]} (:selrect root-shape)
        {:keys [tag]} data
        name (dwc/generate-unique-name unames (str "svg-" (tag-name data)))
        
        shape
        (case tag
          ;; :rect (parse-rect data)
          ;; :path (parse-path name frame-id data)
          (create-raw-svg name frame-id x y width height data))]
    
    (-> shape
        (assoc :svg-id root-id))))

(defn svg-uploaded [data x y]
  (ptk/reify ::svg-uploaded
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            frame-id (cp/frame-id-by-position objects {:x x :y y})

            [width height] (svg-dimensions data)
            x (- x (/ width 2))
            y (- y (/ height 2))

            add-svg-child
            (fn add-svg-child [parent-id root-shape [unames [rchs uchs]] [index {:keys [content] :as data}]]
              (let [shape (parse-svg-element root-shape data unames)
                    shape-id (:id shape)
                    [rch1 uch1] (dwc/add-shape-changes page-id shape)

                    ;; Mov-objects won't have undo because we "delete" the object in the undo of the
                    ;; previous operation
                    rch2 [{:type :mov-objects
                           :parent-id parent-id
                           :frame-id frame-id
                           :page-id page-id
                           :index index
                           :shapes [shape-id]}]

                    ;; Careful! the undo changes are concatenated reversed (we undo in reverse order
                    changes [(d/concat rchs rch1 rch2) (d/concat uch1 uchs)]
                    unames (conj unames (:name shape))]
                (reduce (partial add-svg-child shape-id root-shape) [unames changes] (d/enumerate content))))

            unames (dwc/retrieve-used-names objects)

            svg-name (->> (str/replace (:name data) ".svg" "")
                          (dwc/generate-unique-name unames))

            root-shape (create-raw-svg svg-name frame-id x y width height data)
            root-id (:id root-shape)

            changes (dwc/add-shape-changes page-id root-shape)

            [_ [rchanges uchanges]] (reduce (partial add-svg-child root-id root-shape) [unames changes] (d/enumerate (:content data)))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dwc/select-shapes (d/ordered-set root-id)))))))
