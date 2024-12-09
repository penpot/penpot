;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def comments-local-options (l/derived :options refs/comments-local))

(mf/defc resizing-textarea
  {::mf/wrap-props false}
  [props]
  (let [value            (d/nilv (unchecked-get props "value") "")
        on-focus         (unchecked-get props "on-focus")
        on-blur          (unchecked-get props "on-blur")
        placeholder      (unchecked-get props "placeholder")
        max-length       (unchecked-get props "max-length")
        on-change        (unchecked-get props "on-change")
        on-esc           (unchecked-get props "on-esc")
        on-ctrl-enter    (unchecked-get props "on-ctrl-enter")
        autofocus?       (unchecked-get props "autofocus")
        select-on-focus? (unchecked-get props "select-on-focus")

        local-ref   (mf/use-ref)

        on-change*
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [content (dom/get-target-val event)]
             (on-change content))))

        on-key-down
        (mf/use-fn
         (mf/deps on-esc on-ctrl-enter on-change*)
         (fn [event]
           (cond
             (and (kbd/esc? event) (fn? on-esc)) (on-esc event)
             (and (kbd/mod? event) (kbd/enter? event) (fn? on-ctrl-enter))
             (do
               (on-change* event)
               (on-ctrl-enter event)))))

        on-focus*
        (mf/use-fn
         (mf/deps select-on-focus? on-focus)
         (fn [event]
           (when (fn? on-focus)
             (on-focus event))

           (when ^boolean select-on-focus?
             (let [target (dom/get-target event)]
               (dom/select-text! target)
               ;; In webkit browsers the mouseup event will be called after the on-focus causing and unselect
               (.addEventListener target "mouseup" dom/prevent-default #js {:once true})))))]

    (mf/use-layout-effect
     nil
     (fn []
       (let [node (mf/ref-val local-ref)]
         (set! (.-height (.-style node)) "0")
         (set! (.-height (.-style node)) (str (+ 2 (.-scrollHeight node)) "px")))))

    [:textarea {:ref local-ref
                :auto-focus autofocus?
                :on-key-down on-key-down
                :on-focus on-focus*
                :on-blur on-blur
                :value value
                :placeholder placeholder
                :on-change on-change*
                :max-length max-length}]))

(def ^:private schema:comment-avatar
  [:map
   [:class {:optional true} :string]
   [:image :string]
   [:variant {:optional true}
    [:maybe [:enum "read" "unread" "solved"]]]])

(mf/defc comment-avatar*
  {::mf/props :obj
   ::mf/schema schema:comment-avatar}
  [{:keys [image variant class] :rest props}]
  (let [variant (or variant "read")
        class (dm/str class " " (stl/css-case :avatar true
                                              :avatar-read (= variant "read")
                                              :avatar-unread (= variant "unread")
                                              :avatar-solved (= variant "solved")))
        props (mf/spread-props props {:class class})]
    [:> :div props
     [:img {:src image
            :class (stl/css :avatar-image)}]
     [:div {:class (stl/css :avatar-mask)}]]))

(mf/defc comment-info*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [item profile]}]
  [:*
   [:div {:class (stl/css :author)}
    [:> comment-avatar* {:image (cfg/resolve-profile-photo-url profile)
                         :class (stl/css :avatar-lg)
                         :variant (cond (:is-resolved item) "solved"
                                        (pos? (:count-unread-comments item)) "unread"
                                        :else "read")}]
    [:div {:class (stl/css :identity)}
     [:div {:class (stl/css :fullname)} (:fullname profile)]
     [:div {:class (stl/css :timeago)} (dt/timeago (:modified-at item))]]]

   [:div {:class (stl/css :content)}
    (:content item)]

   [:div {:class (stl/css :replies)}
    (let [total-comments (:count-comments item 1)
          total-replies  (dec total-comments)
          unread-replies (:count-unread-comments item 0)]
      [:*
       (when (> total-replies 0)
         (if (= total-replies 1)
           [:span {:class (stl/css :total-replies)} (str total-replies " " (tr "labels.reply"))]
           [:span {:class (stl/css :total-replies)} (str total-replies " " (tr "labels.replies"))]))

       (when (and (> total-replies 0) (> unread-replies 0))
         (if (= unread-replies 1)
           [:span {:class (stl/css :new-replies)} (str unread-replies " " (tr "labels.reply.new"))]
           [:span {:class (stl/css :new-replies)} (str unread-replies " " (tr "labels.replies.new"))]))])]])

(mf/defc comment-reply-form*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [thread]}]
  (let [show-buttons? (mf/use-state false)
        content       (mf/use-state "")

        disabled? (or (str/blank? @content)
                      (str/empty? @content))

        on-focus
        (mf/use-fn
         #(reset! show-buttons? true))

        on-blur
        (mf/use-fn
         #(reset! show-buttons? false))

        on-change
        (mf/use-fn
         #(reset! content %))

        on-cancel
        (mf/use-fn
         #(do (reset! content "")
              (reset! show-buttons? false)))

        on-submit
        (mf/use-fn
         (mf/deps thread @content)
         (fn []
           (st/emit! (dcm/add-comment thread @content))
           (on-cancel)))]
    [:div {:class (stl/css :comment-reply-form)}
     [:& resizing-textarea {:value @content
                            :placeholder (tr "labels.reply.thread")
                            :autofocus true
                            :on-blur on-blur
                            :on-focus on-focus
                            :select-on-focus? false
                            :on-ctrl-enter on-submit
                            :on-change on-change
                            :max-length 750}]
     (when (or @show-buttons? (seq @content))
       [:div {:class (stl/css :buttons-wrapper)}
        [:> button* {:variant "ghost"
                     :on-click on-cancel}
         (tr "ds.confirm-cancel")]
        [:> button* {:variant "primary"
                     :on-click on-submit
                     :disabled disabled?}
         (tr "labels.post")]])]))

(mf/defc comment-edit-form*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [content on-submit on-cancel]}]
  (let [content (mf/use-state content)

        on-change
        (mf/use-fn
         #(reset! content %))

        on-submit*
        (mf/use-fn
         (mf/deps @content)
         (fn [] (on-submit @content)))

        disabled? (or (str/blank? @content)
                      (str/empty? @content))]

    [:div {:class (stl/css :comment-edit-form)}
     [:& resizing-textarea {:value @content
                            :autofocus true
                            :select-on-focus true
                            :select-on-focus? false
                            :on-ctrl-enter on-submit*
                            :on-change on-change
                            :max-length 750}]
     [:div {:class (stl/css :buttons-wrapper)}
      [:> button* {:variant "ghost"
                   :on-click on-cancel}
       (tr "ds.confirm-cancel")]
      [:> button* {:variant "primary"
                   :on-click on-submit*
                   :disabled disabled?}
       (tr "labels.post")]]]))

(mf/defc comment-floating-thread-draft*
  {::mf/props :obj}
  [{:keys [draft zoom on-cancel on-submit position-modifier]}]
  (let [profile   (mf/deref refs/profile)

        position  (cond-> (:position draft)
                    (some? position-modifier)
                    (gpt/transform position-modifier))
        content   (:content draft)

        pos-x     (* (:x position) zoom)
        pos-y     (* (:y position) zoom)

        disabled? (or (str/blank? content)
                      (str/empty? content))

        on-esc
        (mf/use-fn
         (mf/deps draft)
         (fn [event]
           (dom/stop-propagation event)
           (if (fn? on-cancel)
             (on-cancel)
             (st/emit! :interrupt))))

        on-change
        (mf/use-fn
         (mf/deps draft)
         (fn [content]
           (st/emit! (dcm/update-draft-thread {:content content}))))

        on-submit
        (mf/use-fn
         (mf/deps draft)
         (partial on-submit draft))]

    [:*
     [:div
      {:class (stl/css :comment-floating-bubble)
       :data-testid "floating-thread-bubble"
       :style {:top (str pos-y "px")
               :left (str pos-x "px")}
       :on-click dom/stop-propagation}
      [:> comment-avatar* {:class (stl/css :avatar-lg)
                           :image (cfg/resolve-profile-photo-url profile)}]]
     [:div {:class (stl/css :comment-floating-thread)
            :style {:top (str (- pos-y 24) "px")
                    :left (str (+ pos-x 28) "px")}
            :on-click dom/stop-propagation}
      [:div {:class (stl/css :comment-reply-form)}
       [:& resizing-textarea {:placeholder (tr "labels.write-new-comment")
                              :value (or content "")
                              :autofocus true
                              :select-on-focus? false
                              :on-esc on-esc
                              :on-change on-change
                              :on-ctrl-enter on-submit
                              :max-length 750}]
       [:div {:class (stl/css :buttons-wrapper)}
        [:> button* {:variant "ghost"
                     :on-click on-esc}
         (tr "ds.confirm-cancel")]
        [:> button* {:variant "primary"
                     :on-click on-submit
                     :disabled disabled?}
         (tr "labels.post")]]]]]))

(mf/defc comment-floating-thread-header*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [profiles thread origin]}]
  (let [owner    (get profiles (:owner-id thread))
        profile  (mf/deref refs/profile)
        options  (mf/deref comments-local-options)

        toggle-resolved
        (mf/use-fn
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/update-comment-thread (update thread :is-resolved not)))))

        on-toggle-options
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/toggle-comment-options uuid/zero))))

        delete-thread
        (mf/use-fn
         (fn []
           (st/emit! (dcm/close-thread)
                     (if (= origin :viewer)
                       (dcm/delete-comment-thread-on-viewer thread)
                       (dcm/delete-comment-thread-on-workspace thread)))))

        on-delete-thread
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-comment-thread.title")
                       :message (tr "modals.delete-comment-thread.message")
                       :accept-label (tr "modals.delete-comment-thread.accept")
                       :on-accept delete-thread}))))

        on-hide-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))))]

    [:*
     [:div (tr "labels.comment") " " [:span {:class (stl/css :grayed-text)} "#" (:seqn thread)]]
     [:div {:class (stl/css :header-right)}
      (when (some? thread)
        [:div {:class (stl/css :checkbox-wrapper)
               :title (tr "labels.comment.mark-as-solved")
               :on-click toggle-resolved}
         [:span {:class (stl/css-case :checkbox true
                                      :global/checked (:is-resolved thread))} i/tick]])
      (when (= (:id profile) (:id owner))
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "labels.options")
                          :on-click on-toggle-options
                          :icon "menu"}])]
     [:& dropdown {:show (= options uuid/zero)
                   :on-close on-hide-options}
      [:ul {:class (stl/css :dropdown-menu)}
       [:li {:class (stl/css :dropdown-menu-option)
             :on-click on-delete-thread}
        (tr "labels.delete-comment-thread")]]]]))

(mf/defc comment-floating-thread-item*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [comment thread profiles]}]
  (let [owner    (get profiles (:owner-id comment))
        profile  (mf/deref refs/profile)
        options  (mf/deref comments-local-options)
        edition? (mf/use-state false)

        on-toggle-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/toggle-comment-options (:id comment)))))

        on-hide-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))))

        on-edit-clicked
        (mf/use-fn
         (mf/deps options)
         (fn []
           (st/emit! (dcm/hide-comment-options))
           (reset! edition? true)))

        on-delete-comment
        (mf/use-fn
         (mf/deps comment)
         #(st/emit! (dcm/delete-comment comment)))

        on-submit
        (mf/use-fn
         (mf/deps comment thread)
         (fn [content]
           (reset! edition? false)
           (st/emit! (dcm/update-comment (assoc comment :content content)))))

        on-cancel
        (mf/use-fn #(reset! edition? false))]

    [:div {:class (stl/css :comment-floating-thread-item)}
     [:div {:class (stl/css :container)}
      [:div {:class (stl/css :author)}
       [:> comment-avatar* {:image (cfg/resolve-profile-photo-url owner)}]
       [:div {:class (stl/css :identity)}
        [:div {:class (stl/css :fullname)} (:fullname owner)]
        [:div {:class (stl/css :timeago)} (dt/timeago (:modified-at comment))]]

       (when (= (:id profile) (:id owner))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :on-click on-toggle-options
                           :icon "menu"}])]

      [:div {:class (stl/css :content)}
       (if @edition?
         [:> comment-edit-form* {:content (:content comment)
                                 :on-submit on-submit
                                 :on-cancel on-cancel}]
         [:span {:class (stl/css :text)} (:content comment)])]]

     [:& dropdown {:show (= options (:id comment))
                   :on-close on-hide-options}
      [:ul {:class (stl/css :dropdown-menu)}
       [:li {:class (stl/css :dropdown-menu-option)
             :on-click on-edit-clicked}
        (tr "labels.edit")]
       (when-not thread
         [:li {:class (stl/css :dropdown-menu-option)
               :on-click on-delete-comment}
          (tr "labels.delete-comment")])]]]))

(defn make-comments-ref
  [thread-id]
  (l/derived (l/in [:comments thread-id]) st/state))

(defn- offset-position [position viewport zoom bubble-margin]
  (let [viewport (or viewport {:offset-x 0 :offset-y 0 :width 0 :height 0})
        base-x (+ (* (:x position) zoom) (:offset-x viewport))
        base-y (+ (* (:y position) zoom) (:offset-y viewport))

        x (:x position)
        y (:y position)

        w (:width viewport)
        h (:height viewport)

        comment-width 284 ;; TODO: this is the width set via CSS in an outer container…
                          ;; We should probably do this in a different way.

        orientation-left? (>= (+ base-x comment-width (:x bubble-margin)) w)
        orientation-top?  (>= base-y (/ h 2))

        h-dir (if orientation-left? :left :right)
        v-dir (if orientation-top? :top :bottom)]
    {:x x :y y :h-dir h-dir :v-dir v-dir}))

(mf/defc comment-floating-thread*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [thread zoom profiles origin position-modifier viewport]}]
  (let [ref          (mf/use-ref)
        thread-id    (:id thread)
        thread-pos   (:position thread)

        base-pos     (cond-> thread-pos
                       (some? position-modifier)
                       (gpt/transform position-modifier))

        max-height   (when (some? viewport) (int (* (:height viewport) 0.75)))
                          ;; We should probably look for a better way of doing this.
        bubble-margin {:x 24 :y 24}
        pos          (offset-position base-pos viewport zoom bubble-margin)

        margin-x     (* (:x bubble-margin) (if (= (:h-dir pos) :left) -1 1))
        margin-y     (* (:y bubble-margin) (if (= (:v-dir pos) :top) -1 1))
        pos-x        (+ (* (:x pos) zoom) margin-x)
        pos-y        (- (* (:y pos) zoom) margin-y)

        comments-ref (mf/with-memo [thread-id]
                       (make-comments-ref thread-id))
        comments-map (mf/deref comments-ref)

        comments     (mf/with-memo [comments-map]
                       (->> (vals comments-map)
                            (sort-by :created-at)))

        comment      (first comments)]

    (mf/with-effect [thread-id]
      (st/emit! (dcm/retrieve-comments thread-id)))

    (mf/with-effect [thread-id]
      (st/emit! (dcm/update-comment-thread-status thread-id)))

    (mf/with-layout-effect [thread-pos comments-map]
      (when-let [node (mf/ref-val ref)]
        (dom/scroll-into-view-if-needed! node)))

    (when (some? comment)
      [:div {:class (stl/css-case :comment-floating-thread true
                                  :left (= (:h-dir pos) :left)
                                  :top (= (:v-dir pos) :top))
             :id (str "thread-" thread-id)
             :style {:left (str pos-x "px")
                     :top (str pos-y "px")
                     :max-height max-height}
             :on-click dom/stop-propagation}

       [:div {:class (stl/css :header)}
        [:> comment-floating-thread-header* {:profiles profiles
                                             :thread thread
                                             :origin origin}]]

       [:div {:class (stl/css :main)}
        [:> comment-floating-thread-item* {:comment comment
                                           :profiles profiles
                                           :thread thread}]
        (for [item (rest comments)]
          [:* {:key (dm/str (:id item))}
           [:> comment-floating-thread-item* {:comment item
                                              :profiles profiles}]])]

       [:> comment-reply-form* {:thread thread}]])))

(mf/defc comment-floating-bubble*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [thread profiles zoom is-open on-click origin position-modifier]}]
  (let [owner        (get profiles (:owner-id thread))

        pos          (cond-> (:position thread)
                       (some? position-modifier)
                       (gpt/transform position-modifier))

        drag?        (mf/use-ref nil)
        was-open?    (mf/use-ref nil)

        dragging-ref (mf/use-ref false)
        start-ref    (mf/use-ref nil)

        position     (:position thread)
        frame-id     (:frame-id thread)

        state        (mf/use-state {:hover? false
                                    :grabbing? false
                                    :new-position-x nil
                                    :new-position-y nil
                                    :new-frame-id frame-id})

        pos-x        (* (or (:new-position-x @state) (:x pos)) zoom)
        pos-y        (* (or (:new-position-y @state) (:y pos)) zoom)

        on-pointer-down
        (mf/use-fn
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! dragging-ref true)
           (mf/set-ref-val! start-ref (dom/get-client-position event))))

        on-pointer-down*
        (mf/use-fn
         (mf/deps origin was-open? is-open drag? on-pointer-down)
         (fn [event]
           (swap! state assoc :grabbing? true)
           (when (not= origin :viewer)
             (mf/set-ref-val! was-open? is-open)
             (when is-open (st/emit! (dcm/close-thread)))
             (mf/set-ref-val! drag? false)
             (dom/stop-propagation event)
             (on-pointer-down event))))

        on-pointer-up
        (mf/use-fn
         (mf/deps (select-keys @state [:new-position-x :new-position-y :new-frame-id]))
         (fn [_ thread]
           (when (and
                  (some? (:new-position-x @state))
                  (some? (:new-position-y @state)))
             (st/emit! (dwcm/update-comment-thread-position thread [(:new-position-x @state)
                                                                    (:new-position-y @state)])))))

        on-pointer-up*
        (mf/use-fn
         (mf/deps origin thread was-open? drag? on-pointer-up)
         (fn [event]
           (swap! state assoc :grabbing? false)
           (when (not= origin :viewer)
             (dom/stop-propagation event)
             (on-pointer-up event thread)

             (when (or (and (mf/ref-val was-open?) (mf/ref-val drag?))
                       (and (not (mf/ref-val was-open?)) (not (mf/ref-val drag?))))
               (st/emit! (dcm/open-thread thread))))))

        on-pointer-move
        (mf/use-fn
         (mf/deps position zoom)
         (fn [event]
           (when-let [_ (mf/ref-val dragging-ref)]
             (let [start-pt (mf/ref-val start-ref)
                   current-pt (dom/get-client-position event)
                   delta-x (/ (- (:x current-pt) (:x start-pt)) zoom)
                   delta-y (/ (- (:y current-pt) (:y start-pt)) zoom)]
               (swap! state assoc
                      :new-position-x (+ (:x position) delta-x)
                      :new-position-y (+ (:y position) delta-y))))))

        on-pointer-move*
        (mf/use-fn
         (mf/deps origin drag? on-pointer-move)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! drag? true)
             (dom/stop-propagation event)
             (on-pointer-move event))))

        on-lost-pointer-capture
        (mf/use-fn
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)
           (swap! state assoc :new-position-x nil)
           (swap! state assoc :new-position-y nil)))

        on-mouse-enter
        (mf/use-fn
         (mf/deps is-open)
         (fn [event]
           (dom/stop-propagation event)
           (when (false? is-open)
             (swap! state assoc :hover? true))))

        on-mouse-leave
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! state assoc :hover? false)))

        on-click*
        (mf/use-fn
         (mf/deps origin thread on-click)
         (fn [event]
           (dom/stop-propagation event)
           (when (or (and (mf/ref-val was-open?) (mf/ref-val drag?))
                     (and (not (mf/ref-val was-open?)) (not (mf/ref-val drag?))))
             (swap! state assoc :hover? false))
           (when (= origin :viewer)
             (on-click thread))))]

    [:div {:style {:top (str pos-y "px")
                   :left (str pos-x "px")}
           :on-pointer-down on-pointer-down*
           :on-pointer-up on-pointer-up*
           :on-pointer-move on-pointer-move*
           :on-mouse-enter on-mouse-enter
           :on-mouse-leave on-mouse-leave
           :on-click on-click*
           :on-lost-pointer-capture on-lost-pointer-capture
           :data-testid (str "floating-thread-bubble-" (:seqn thread))
           :class (stl/css-case :comment-floating-bubble true
                                :grabbing (true? (:grabbing? @state)))}

     (if (true? (:hover? @state))

       [:div {:class (stl/css :comment-floating-thread :abbreviated)}
        [:div {:class (stl/css :comment-floating-thread-item)}
         [:div {:class (stl/css :container)}
          [:> comment-info* {:item thread
                             :profile owner}]]]]

       [:> comment-avatar* {:image (cfg/resolve-profile-photo-url owner)
                            :class (stl/css :avatar-lg)
                            :variant (cond (:is-resolved thread) "solved"
                                           (pos? (:count-unread-comments thread)) "unread"
                                           :else "read")}])]))

(mf/defc comment-sidebar-thread-item*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [item profiles on-click]}]
  (let [owner (get profiles (:owner-id item))

        frame (mf/deref (refs/workspace-page-object-by-id (:page-id item) (:frame-id item)))

        on-click*
        (mf/use-fn
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

    [:div {:class (stl/css :comment-thread-cover)
           :on-click on-click*}
     [:div {:class (stl/css :location)}
      [:div
       [:div {:class (stl/css :location-text)}
        (str "#" (:seqn item))
        (str " - " (:page-name item))
        (when (and (some? frame) (not (cfh/root? frame)))
          (str " - " (:name frame)))]]]

     [:> comment-info* {:item item
                        :profile owner}]]))

(mf/defc comment-sidebar-thread-group*
  {::mf/props :obj}
  [{:keys [group profiles on-thread-click]}]
  [:div
   (for [item (:items group)]
     [:> comment-sidebar-thread-item*
      {:item item
       :on-click on-thread-click
       :profiles profiles
       :key (:id item)}])])

(mf/defc comment-dashboard-thread-item*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [item profiles on-click]}]
  (let [owner (get profiles (:owner-id item))

        on-click*
        (mf/use-fn
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

    [:div {:class (stl/css :comment-thread-cover)
           :on-click on-click*}
     [:div {:class (stl/css :location)}
      [:div
       [:div {:class (stl/css :location-icon)}
        [:> icon* {:id "comments"}]]
       [:div {:class (stl/css :location-text)}
        (str "#" (:seqn item))
        (str " " (:file-name item))
        (str ", " (:page-name item))]]]

     [:> comment-info* {:item item
                        :profile owner}]]))

(mf/defc comment-dashboard-thread-group*
  {::mf/props :obj}
  [{:keys [group profiles on-thread-click]}]
  [:div
   (for [item (:items group)]
     [:> comment-dashboard-thread-item*
      {:item item
       :on-click on-thread-click
       :profiles profiles
       :key (:id item)}])])
