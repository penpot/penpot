;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.interactions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.pages.helpers :as cph]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dcm]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.shapes :as shapes]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(defn prepare-objects
  [frame size objects]
  (let [
        frame-id  (:id frame)
        modifier  (-> (gpt/point (:x size) (:y size))
                      (gpt/negate)
                      (gmt/translate-matrix))

        update-fn #(d/update-when %1 %2 assoc-in [:modifiers :displacement] modifier)]

    (->> (cph/get-children-ids objects frame-id)
         (into [frame-id])
         (reduce update-fn objects))))

(mf/defc viewport-svg
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [page    (unchecked-get props "page")
        frame   (unchecked-get props "frame")
        base    (unchecked-get props "base")
        offset  (unchecked-get props "offset")
        size    (unchecked-get props "size")

        vbox    (:vbox size)

        fixed-ids (filter :fixed-scroll (vals (:objects page)))

        ;; we have con consider the children if the fixed element is a group
        fixed-children-ids (into #{} (mapcat #(cph/get-children-ids (:objects page) (:id %)) fixed-ids))

        parent-children-ids (->> fixed-ids
                                 (mapcat #(cons (:id %) (cph/get-parent-ids (:objects page) (:id %))))
                                 (remove #(= % uuid/zero)))

        fixed-ids (concat fixed-children-ids parent-children-ids)

        not-fixed-ids (->> (remove (set fixed-ids) (keys (:objects page)))
                           (remove #(= % uuid/zero)))

        calculate-objects (fn [ids] (->> ids
                                         (map (d/getf (:objects page)))
                                         (concat [frame])
                                         (d/index-by :id)
                                         (prepare-objects frame size)))

        wrapper-fixed (mf/with-memo [page frame size]
                        (shapes/frame-container-factory (calculate-objects fixed-ids)))

        objects-not-fixed (mf/with-memo [page frame size]
                            (calculate-objects not-fixed-ids))

        wrapper-not-fixed (mf/with-memo [objects-not-fixed]
                            (shapes/frame-container-factory objects-not-fixed))

        ;; Retrieve frames again with correct modifier
        frame   (get objects-not-fixed (:id frame))
        base    (get objects-not-fixed (:id base))]

    [:& (mf/provider shapes/base-frame-ctx) {:value base}
     [:& (mf/provider shapes/frame-offset-ctx) {:value offset}
      ;; We have two different svgs for fixed and not fixed elements so we can emulate the sticky css attribute in svg
      [:svg.not-fixed {:view-box vbox
                       :width (:width size)
                       :height (:height size)
                       :version "1.1"
                       :xmlnsXlink "http://www.w3.org/1999/xlink"
                       :xmlns "http://www.w3.org/2000/svg"
                       :fill "none"}
       [:& wrapper-not-fixed {:shape frame :view-box vbox}]]
      [:svg.fixed {:view-box vbox
                   :width (:width size)
                   :height (:height size)
                   :version "1.1"
                   :xmlnsXlink "http://www.w3.org/1999/xlink"
                   :xmlns "http://www.w3.org/2000/svg"
                   :fill "none"
                   :style {:width (:width size)
                           :height (:height size)}}
       [:& wrapper-fixed {:shape (dissoc frame :fills) :view-box vbox}]]]]))

(mf/defc viewport
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [;; NOTE: with `use-equal-memo` hook we ensure that all values
        ;; conserves the reference identity for avoid unnecesary dummy
        ;; rerenders.
        mode   (h/use-equal-memo (unchecked-get props "interactions-mode"))
        offset (h/use-equal-memo (unchecked-get props "frame-offset"))
        size   (h/use-equal-memo (unchecked-get props "size"))

        page   (unchecked-get props "page")
        frame  (unchecked-get props "frame")
        base   (unchecked-get props "base-frame")]

    (mf/with-effect [mode]
      (let [on-click
            (fn [_]
              (when (= mode :show-on-click)
                (st/emit! (dv/flash-interactions))))

            on-mouse-wheel
            (fn [event]
              (when (kbd/mod? event)
                (dom/prevent-default event)
                (let [event (dom/event->browser-event event)
                      delta (+ (.-deltaY ^js event)
                               (.-deltaX ^js event))]
                  (if (pos? delta)
                    (st/emit! dv/decrease-zoom)
                    (st/emit! dv/increase-zoom)))))

            on-key-down
            (fn [event]
              (when (kbd/esc? event)
                (st/emit! (dcm/close-thread))))


            ;; bind with passive=false to allow the event to be cancelled
            ;; https://stackoverflow.com/a/57582286/3219895
            key1 (events/listen goog/global "wheel" on-mouse-wheel #js {"passive" false})
            key2 (events/listen goog/global "keydown" on-key-down)
            key3 (events/listen goog/global "click" on-click)]
        (fn []
          (events/unlistenByKey key1)
          (events/unlistenByKey key2)
          (events/unlistenByKey key3))))

    [:& viewport-svg {:page page
                      :frame frame
                      :base base
                      :offset offset
                      :size size}]))

(mf/defc flows-menu
  {::mf/wrap [mf/memo]}
  [{:keys [page index]}]
  (let [flows        (dm/get-in page [:options :flows])
        frames       (:frames page)
        frame        (get frames index)
        current-flow (mf/use-state
                       (ctp/get-frame-flow flows (:id frame)))

        show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        select-flow
        (mf/use-callback
         (fn [flow]
           (reset! current-flow flow)
           (st/emit! (dv/go-to-frame (:starting-frame flow)))))]

    (when (seq flows)
      [:div.view-options {:on-click toggle-dropdown}
       [:span.icon i/play]
       [:span.label (:name @current-flow)]
       [:span.icon i/arrow-down]
       [:& dropdown {:show @show-dropdown?
                     :on-close hide-dropdown}
        [:ul.dropdown.with-check
         (for [[index flow] (d/enumerate flows)]
           [:li {:key (dm/str "flow-" (:id flow) "-" index)
                 :class (dom/classnames :selected (= (:id flow) (:id @current-flow)))
                 :on-click #(select-flow flow)}
            [:span.icon i/tick]
            [:span.label (:name flow)]])]]])))

(mf/defc interactions-menu
  []
  (let [local           (mf/deref refs/viewer-local)
        mode            (:interactions-mode local)

        show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        select-mode
        (mf/use-callback
         (fn [mode]
           (st/emit! (dv/set-interactions-mode mode))))]

    [:div.view-options {:on-click toggle-dropdown}
     [:span.label (tr "viewer.header.interactions")]
     [:span.icon i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul.dropdown.with-check
       [:li {:class (dom/classnames :selected (= mode :hide))
             :on-click #(select-mode :hide)}
        [:span.icon i/tick]
        [:span.label (tr "viewer.header.dont-show-interactions")]]

       [:li {:class (dom/classnames :selected (= mode :show))
             :on-click #(select-mode :show)}
        [:span.icon i/tick]
        [:span.label (tr "viewer.header.show-interactions")]]

       [:li {:class (dom/classnames :selected (= mode :show-on-click))
             :on-click #(select-mode :show-on-click)}
        [:span.icon i/tick]
        [:span.label (tr "viewer.header.show-interactions-on-click")]]]]]))


(defn animate-go-to-frame
  [animation current-viewport orig-viewport current-size orig-size wrapper-size]
  (case (:animation-type animation)

    :dissolve
    (do (dom/animate! orig-viewport
                      [#js {:opacity "100"}
                       #js {:opacity "0"}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! current-viewport
                      [#js {:opacity "0"}
                       #js {:opacity "100"}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}))

    :slide
    (case (:way animation)

      :in
      (case (:direction animation)

        :right
        (let [offset (+ (:width current-size)
                        (/ (- (:width wrapper-size) (:width current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:left (str "-" offset "px")}
                         #js {:left "0"}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:left "0"
                                :opacity "100%"}
                           #js {:left (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))

        :left
        (let [offset (+ (:width current-size)
                        (/ (- (:width wrapper-size) (:width current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:right (str "-" offset "px")}
                         #js {:right "0"}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:right "0"
                                :opacity "100%"}
                           #js {:right (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))

        :up
        (let [offset (+ (:height current-size)
                        (/ (- (:height wrapper-size) (:height current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:bottom (str "-" offset "px")}
                         #js {:bottom "0"}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:bottom "0"
                                :opacity "100%"}
                           #js {:bottom (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))

        :down
        (let [offset (+ (:height current-size)
                        (/ (- (:height wrapper-size) (:height current-size)) 2))]
          (dom/animate! current-viewport
                        [#js {:top (str "-" offset "px")}
                         #js {:top "0"}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! orig-viewport
                          [#js {:top "0"
                                :opacity "100%"}
                           #js {:top (str (* offset 0.2) "px")
                                :opacity "0"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))}))))

      :out
      (case (:direction animation)

        :right
        (let [offset (+ (:width orig-size)
                        (/ (- (:width wrapper-size) (:width orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:right "0"}
                         #js {:right (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:right (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:right "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))

        :left
        (let [offset (+ (:width orig-size)
                        (/ (- (:width wrapper-size) (:width orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:left "0"}
                         #js {:left (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:left (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:left "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))

        :up
        (let [offset (+ (:height orig-size)
                        (/ (- (:height wrapper-size) (:height orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:top "0"}
                         #js {:top (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:top (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:top "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))

        :down
        (let [offset (+ (:height orig-size)
                        (/ (- (:height wrapper-size) (:height orig-size)) 2))]
          (dom/set-css-property! orig-viewport "z-index" 10000)
          (dom/animate! orig-viewport
                        [#js {:bottom "0"}
                         #js {:bottom (str "-" offset "px")}]
                        #js {:duration (:duration animation)
                             :easing (name (:easing animation))}
                        #(st/emit! (dv/complete-animation)))
          (when (:offset-effect animation)
            (dom/animate! current-viewport
                          [#js {:bottom (str (* offset 0.2) "px")
                                :opacity "0"}
                           #js {:bottom "0"
                                :opacity "100%"}]
                          #js {:duration (:duration animation)
                               :easing (name (:easing animation))})))))

    :push
    (case (:direction animation)

      :right
      (let [offset (:width wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:left (str "-" offset "px")}
                       #js {:left "0"}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:left "0"}
                       #js {:left (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}))

      :left
      (let [offset (:width wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:right (str "-" offset "px")}
                       #js {:right "0"}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:right "0"}
                       #js {:right (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}))

      :up
      (let [offset (:height wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:bottom (str "-" offset "px")}
                       #js {:bottom "0"}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:bottom "0"}
                       #js {:bottom (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}))

      :down
      (let [offset (:height wrapper-size)]
        (dom/animate! current-viewport
                      [#js {:top (str "-" offset "px")}
                       #js {:top "0"}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))
        (dom/animate! orig-viewport
                      [#js {:top "0"}
                       #js {:top (str offset "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))})))))

(defn animate-open-overlay
  [animation overlay-viewport
   wrapper-size overlay-size overlay-position]
  (when (some? overlay-viewport)
    (case (:animation-type animation)

      :dissolve
      (dom/animate! overlay-viewport
                    [#js {:opacity "0"}
                     #js {:opacity "100"}]
                    #js {:duration (:duration animation)
                         :easing (name (:easing animation))}
                    #(st/emit! (dv/complete-animation)))

      :slide
      (case (:direction animation) ;; way and offset-effect are ignored

        :right
        (dom/animate! overlay-viewport
                      [#js {:left (str "-" (:width overlay-size) "px")}
                       #js {:left (str (:x overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))

        :left
        (dom/animate! overlay-viewport
                      [#js {:left (str (:width wrapper-size) "px")}
                       #js {:left (str (:x overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))

        :up
        (dom/animate! overlay-viewport
                      [#js {:top (str (:height wrapper-size) "px")}
                       #js {:top (str (:y overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))

        :down
        (dom/animate! overlay-viewport
                      [#js {:top (str "-" (:height overlay-size) "px")}
                       #js {:top (str (:y overlay-position) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)))))))

(defn animate-close-overlay
  [animation overlay-viewport
   wrapper-size overlay-size overlay-position overlay-id]
  (when (some? overlay-viewport)
    (case (:animation-type animation)

      :dissolve
      (dom/animate! overlay-viewport
                    [#js {:opacity "100"}
                     #js {:opacity "0"}]
                    #js {:duration (:duration animation)
                         :easing (name (:easing animation))}
                    #(st/emit! (dv/complete-animation)
                               (dv/close-overlay overlay-id)))

      :slide
      (case (:direction animation) ;; way and offset-effect are ignored

        :right
        (dom/animate! overlay-viewport
                      [#js {:left (str (:x overlay-position) "px")}
                       #js {:left (str (:width wrapper-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))

        :left
        (dom/animate! overlay-viewport
                      [#js {:left (str (:x overlay-position) "px")}
                       #js {:left (str "-" (:width overlay-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))

        :up
        (dom/animate! overlay-viewport
                      [#js {:top (str (:y overlay-position) "px")}
                       #js {:top (str "-" (:height overlay-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))

        :down
        (dom/animate! overlay-viewport
                      [#js {:top (str (:y overlay-position) "px")}
                       #js {:top (str (:height wrapper-size) "px")}]
                      #js {:duration (:duration animation)
                           :easing (name (:easing animation))}
                      #(st/emit! (dv/complete-animation)
                                 (dv/close-overlay overlay-id)))))))

