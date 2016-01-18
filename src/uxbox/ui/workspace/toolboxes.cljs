(ns uxbox.ui.workspace.toolboxes
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.util.data :refer (read-string)]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:static drawing-shape
  "A focused vision of the drawing property
  of the workspace status. This avoids
  rerender the whole toolbox on each workspace
  change."
  (as-> (l/in [:workspace :drawing]) $
    (l/focus-atom $ st/state)))

(def ^:static ^:private shapes-by-id
  (as-> (l/key :shapes-by-id) $
    (l/focus-atom $ st/state)))

(defn- focus-page
  [pageid]
  (as-> (l/in [:pages-by-id pageid]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tools
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:staric +draw-tools+
  {:rect
   {:icon i/box
    :help "Box (Ctrl + B)"
    :priority 10}
   :circle
   {:icon i/circle
    :help "Circle (Ctrl + E)"
    :priority 20}
   :line
   {:icon i/line
    :help "Line (Ctrl + L)"
    :priority 30}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Draw Tool Box
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn draw-tools-render
  [open-toolboxes]
  (let [workspace (rum/react wb/workspace-l)
        close #(rs/emit! (dw/toggle-toolbox :draw))
        tools (->> (into [] +draw-tools+)
                   (sort-by (comp :priority second)))]
    (html
     [:div#form-tools.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span "Tools"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       (for [[key props] tools]
         [:div.tool-btn.tooltip.tooltip-hover
          {:alt (:help props)
           :key (name key)
           :on-click (constantly nil)}
          (:icon props)])]])))

(def ^:static draw-tools
  (mx/component
   {:render draw-tools-render
    :name "draw-tools"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-shape
  [selected item event]
  (dom/prevent-default event)
  (let [id (:id item)]
    (cond
      (or (:blocked item)
          (:hidden item))
      nil

      (.-ctrlKey event)
      (rs/emit! (dw/select-shape id))

      (> (count selected) 1)
      (rs/emit! (dw/deselect-all)
                (dw/select-shape id))

      (contains? selected id)
      (rs/emit! (dw/select-shape id))

      :else
      (rs/emit! (dw/deselect-all)
                (dw/select-shape id)))))

(defn- toggle-visibility
  [selected item event]
  (dom/stop-propagation event)
  (let [id (:id item)]
    (rs/emit! (dw/toggle-shape-visibility id))
    (when (contains? selected id)
      (rs/emit! (dw/select-shape id)))))

(defn- toggle-blocking
  [item event]
  (dom/stop-propagation event)
  (let [id (:id item)]
    (rs/emit! (dw/toggle-shape-blocking id))))

(defn- layer-element-render
  [own item selected]
  (let [selected? (contains? selected (:id item))
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)]
    (html
     [:li {:key (str (:id item))
           :on-click select
           :class (when selected? "selected")}
      [:div.element-list-body {:class (when selected? "selected")}
       [:div.element-actions
        [:div.toggle-element {:class (when-not (:hidden item) "selected")
                              :on-click toggle-visibility}
         i/eye]
        [:div.block-element {:class (when (:blocked item) "selected")
                             :on-click toggle-blocking}
         i/lock]]

       (if (:group item)
         [:div.sublevel-element i/sublevel])
       [:div.element-icon (shapes/-render-svg item)]
       [:span (or (:name item)
                  (:id item))]]])))

(def ^:static ^:private layer-element
  (mx/component
   {:render layer-element-render
    :name "layer-element"
    :mixins [mx/static]}))

(declare layer-group)

(defn- layer-group-render
  [own item selected]
  (let [local (:rum/local own)
        selected? (contains? selected (:id item))
        open? (:open @local true)
        select #(select-shape selected item %)
        toggle-visibility #(toggle-visibility selected item %)
        toggle-blocking #(toggle-blocking item %)
        toggle-open (fn [event]
                      (dom/stop-propagation event)
                      (swap! local assoc :open (not open?)))
        shapes-by-id (rum/react shapes-by-id)]
    (html
     [:li.group {:class (when open? "open")}
      [:div.element-list-body {:class (when selected? "selected")
                               :on-click select}
       [:div.element-actions
        [:div.toggle-element {:class (when-not (:hidden item) "selected")
                              :on-click toggle-visibility}
         i/eye]
        [:div.block-element {:class (when (:blocked item) "selected")
                             :on-click toggle-blocking}
         i/lock]
        [:div.chain-element i/chain]]
       [:div.element-icon i/folder]
       [:span (:name item "Unnamed group")]
       [:span.toggle-content {:on-click toggle-open}
        i/arrow-slide]]
      (if open?
        [:ul
         (for [shape (map #(get shapes-by-id %) (:items item))
               :let [key (str (:id shape))]]
           ;; TODO: make polymorphic
           (case (:type shape)
             :builtin/icon (rum/with-key (layer-element shape selected) key)
             :builtin/group (rum/with-key (layer-group shape selected) key)))])])))

(def ^:static ^:private layer-group
  (mx/component
   {:render layer-group-render
    :name "layer-group"
    :mixins [mx/static rum/reactive (mx/local)]}))

(defn layers-render
  [own]
  (let [workspace (rum/react wb/workspace-l)
        selected (:selected workspace)
        shapes-by-id (rum/react shapes-by-id)
        page (rum/react (focus-page (:page workspace)))
        close #(rs/emit! (dw/toggle-toolbox :layers))
        copy #(rs/emit! (dw/copy-selected))
        group #(rs/emit! (dw/group-selected))
        delete #(rs/emit! (dw/delete-selected))]
    (html
     [:div#layers.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/layers]
       [:span "Layers"]
       [:div.tool-window-close {:on-click close} i/close]]
      [:div.tool-window-content
       [:ul.element-list
        (for [shape (map #(get shapes-by-id %) (:shapes page))
              :let [key (str (:id shape))]]
          ;; TODO: make polymorphic
          (case (:type shape)
            :builtin/icon (rum/with-key (layer-element shape selected) key)
            :builtin/group (rum/with-key (layer-group shape selected) key)))]]
      [:div.layers-tools
       [:ul.layers-tools-content
        [:li.clone-layer {:on-click copy}
         i/copy]
        [:li.group-layer {:on-click group}
         i/folder]
        [:li.delete-layer {:on-click delete}
         i/trash]]]])))

(def ^:static layers
  (mx/component
   {:render layers-render
    :name "layers"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-icon
  [icon]
  (if (= (:drawing @wb/workspace-l) icon)
    (rs/emit! (dw/select-for-drawing nil))
    (rs/emit! (dw/select-for-drawing icon))))

(defn- change-icon-coll
  [local event]
  (let [value (-> (dom/event->value event)
                  (read-string))]
    (swap! local assoc :collid value)
    (rs/emit! (dw/select-for-drawing nil))))

(defn- icon-wrapper-render
  [own icon]
  (shapes/-render-svg icon nil))

(def ^:static ^:private icon-wrapper
  (mx/component
   {:render icon-wrapper-render
    :name "icon-wrapper"
    :mixins [mx/static]}))

(defn icons-render
  [own]
  (let [local (:rum/local own)
        drawing (rum/react drawing-shape)
        collid (:collid @local)
        icons (get-in library/+icon-collections-by-id+ [collid :icons])
        on-close #(rs/emit! (dw/toggle-toolbox :icons))
        on-select #(select-icon %)
        on-change #(change-icon-coll local %)]
    (html
     [:div#form-figures.tool-window
      [:div.tool-window-bar
       [:div.tool-window-icon i/window]
       [:span "Icons"]
       [:div.tool-window-close {:on-click on-close} i/close]]
      [:div.tool-window-content
       [:div.figures-catalog
        ;; extract component: set selector
        [:select.input-select.small {:on-change on-change
                                     :value collid}
         (for [icon-coll library/+icon-collections+]
           [:option {:key (str "icon-coll" (:id icon-coll))
                     :value (pr-str (:id icon-coll))}
            (:name icon-coll)])]]
       (for [icon icons
             :let [selected? (= drawing icon)]]
         [:div.figure-btn {:key (str (:id icon))
                           :class (when selected? "selected")
                           :on-click #(on-select icon)}
          (icon-wrapper icon)])]])))

(def ^:static icons
  (mx/component
   {:render icons-render
    :name "icons-toolbox"
    :mixins [rum/reactive
             (mx/local {:collid 1 :builtin true})]}))
