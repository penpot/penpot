;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.clipboard
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.transit :as t]
   [app.common.types.path :as path]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.edition :as edition]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.tools :as tools]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.streams :as ms]
   [app.util.clipboard :as clipboard]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private clipboard-type :copied-path-content)

(defn- on-clipboard-error
  [cause]
  (js/console.error "clipboard blocked:" cause)
  (rx/empty))

(defn copy-selected-nodes
  "Copies the selected path content to the clipboard."
  []
  (ptk/reify ::copy-selected-nodes
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)
            fragment  (some-> content (path/extract-content selection))]
        (when (seq fragment)
          (let [data (t/encode-str {:type clipboard-type
                                    :content fragment}
                                   {:type :json-verbose})]
            (->> (rx/from (clipboard/to-clipboard data))
                 (rx/catch on-clipboard-error)
                 (rx/ignore))))))))

(defn cut-selected-nodes
  "Copies and removes the current path selection."
  []
  (ptk/reify ::cut-selected-nodes
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (copy-selected-nodes)
             (tools/delete-selected)))))

(def ^:private paste-offset (gpt/point 10 10))

(defn- collision-step
  "Returns the non-negative paste-offset step that makes two nodes coincide."
  [pasted existing]
  (let [delta  (gpt/subtract existing pasted)
        x-step (/ (:x delta) (:x paste-offset))
        y-step (/ (:y delta) (:y paste-offset))]
    (when (and (not (neg? x-step))
               (= x-step y-step)
               (= x-step (mth/floor x-step)))
      (long x-step))))

(defn- available-offset-step
  "Returns the first paste-offset step with no node collisions."
  [existing pasted]
  (let [blocked
        (reduce
         (fn [blocked pasted-point]
           (reduce
            (fn [blocked existing-point]
              (if-let [step (collision-step pasted-point existing-point)]
                (conj blocked step)
                blocked))
            blocked
            existing))
         #{}
         pasted)]
    ;; At most (count blocked) non-negative steps can be unavailable.
    (some #(when-not (contains? blocked %) %)
          (range (inc (count blocked))))))

(defn- center-content-at
  "Centers `sub-content` on `target` using its node bounds."
  [sub-content target]
  (let [pts    (path/get-points sub-content)
        xs     (map :x pts)
        ys     (map :y pts)
        center (gpt/point (/ (+ (reduce min xs) (reduce max xs)) 2)
                          (/ (+ (reduce min ys) (reduce max ys)) 2))]
    (path/move-content sub-content (gpt/subtract target center))))

(defn- offset-pasted-content
  "Offsets pasted content until its nodes do not overlap existing nodes."
  [content sub-content]
  (let [existing (into #{} (path/get-points content))
        pasted   (path/get-points sub-content)
        step     (available-offset-step existing pasted)]
    (if (zero? step)
      sub-content
      (path/move-content sub-content (gpt/scale paste-offset step)))))

(defn paste-content
  "Pastes path content into the edited path at the pointer."
  [sub-content]
  (ptk/reify ::paste-content
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (if (and (some? id)
                 (some? (dm/get-in state [:workspace-local :edit-path id]))
                 (seq sub-content))
          (let [content     (st/get-path state :content)
                base        (count content)
                target      (deref ms/mouse-position)
                ;; Center the fragment at the pointer.
                sub-content (cond-> sub-content
                              (some? target) (center-content-at target))
                sub-content (offset-pasted-content content sub-content)
                new-content (path/splice-content content sub-content)
                pasted      (into #{}
                                  (map #(+ base %))
                                  (helpers/node-indices sub-content))]
            (-> state
                (st/set-content new-content)
                (update-in (st/get-path-location state) path/update-geometry)
                (assoc-in [:workspace-local :edit-path id :selection]
                          (assoc helpers/empty-selection :nodes pasted))))
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      ;; Enter move mode with the pasted nodes selected.
      (when (some? (dm/get-in state [:workspace-local :edition]))
        (rx/of (drawing/change-edit-mode :move))))))

(defn paste-nodes-as-shape
  "Creates a path shape from copied content at the pointer."
  [sub-content]
  (ptk/reify ::paste-nodes-as-shape
    ptk/WatchEvent
    (watch [_ state _]
      (let [content  (path/content sub-content)
            id       (st/get-path-id state)
            editing? (and (some? id)
                          (some? (dm/get-in state [:workspace-local :edit-path id])))]
        (when (and (not editing?) (seq (path/get-points content)))
          (let [target (or (deref ms/mouse-position)
                           (dsh/get-viewport-center state))
                moved  (center-content-at content target)
                mrect  (path/calc-selrect moved)]
            (rx/of
             (dwsh/create-and-add-shape
              :path (:x target) (:y target)
              {:content moved
               ;; Keep the shape at the content position.
               :x (:x mrect)
               :y (:y mrect)
               :width (:width mrect)
               :height (:height mrect)
               :name "Path"}))))))))

(defn paste-nodes
  "Pastes copied path content into the edited path."
  []
  (ptk/reify ::paste-nodes
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (clipboard/from-navigator)
           (rx/filter #(= (.-type ^js %) "application/transit+json"))
           (rx/mapcat #(rx/from (.text ^js %)))
           (rx/map t/decode-str)
           (rx/filter #(and (map? %) (= clipboard-type (:type %))))
           (rx/take 1)
           (rx/mapcat (fn [{:keys [content]}]
                        ;; Drop a pending segment before splicing.
                        (rx/of (common/cancel-pending-segment)
                               (paste-content content))))
           (rx/catch on-clipboard-error)))))

(defn duplicate-selected
  "Duplicates the current node and segment selection."
  []
  (ptk/reify ::duplicate-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (st/get-selection state id)
            zoom      (dm/get-in state [:workspace-local :zoom] 1)
            result    (helpers/duplicate-selection-content
                       content selection (edition/duplicate-offset zoom))]
        (when (seq (:sub result))
          (rx/concat
           ;; Drop a pending segment before splicing.
           (rx/of (common/cancel-pending-segment)
                  (edition/splice-duplicated result))
           (when (some? (dm/get-in state [:workspace-local :edition]))
             (rx/of (drawing/change-edit-mode :move)))))))))
