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
   [app.common.math :as mth]
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
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [clojure.math :refer [floor]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def comments-local-options (l/derived :options refs/comments-local))

(def mentions-context (mf/create-context nil))

(def r-mentions-split #"@\[[^\]]*\]\([^\)]*\)")
(def r-mentions #"@\[([^\]]*)\]\(([^\)]*)\)")


(defn- parse-comment
  "Parse a comment into its elements (texts and mentions)"
  [comment]
  (d/interleave-all
   (->> (str/split comment r-mentions-split)
        (map #(hash-map :type :text :content %)))

   (->> (re-seq r-mentions comment)
        (map (fn [[_ user id]]
               {:type :mention
                :content user
                :data {:id id}})))))

(defn parse-nodes
  "Parse the nodes to format a comment"
  [node]
  (->> (dom/get-children node)
       (map
        (fn [node]
          (cond
            (and (instance? js/HTMLElement node) (dom/get-data node "user-id"))
            (str/ffmt "@[%](%)" (.-textContent node) (dom/get-data node "user-id"))

            :else
            (.-textContent node))))
       (str/join "")))


(defn create-text-node
  "Creates a text-only node"
  ([]
   (create-text-node ""))
  ([text]
   (-> (dom/create-element "span")
       (dom/set-data! "type" "text")
       (dom/set-html! (if (empty? text) "&#8203;" text)))))

(defn create-mention-node
  "Creates a mention node"
  [id fullname]
  (-> (dom/create-element "span")
      (dom/set-data! "type" "mention")
      (dom/set-data! "user-id" (dm/str id))
      (dom/set-data! "fullname" fullname)
      (obj/set! "textContent" fullname)))

(defn current-text-node
  "Retrieves the text node and the offset that the cursor is positioned on"
  [node]

  (let [selection     (wapi/get-selection)
        range         (wapi/get-range selection 0)
        anchor-node   (wapi/range-start-container range)
        anchor-offset (wapi/range-start-offset range)]
    (when (and node (.contains node anchor-node))
      (let [span-node
            (if (instance? js/Text anchor-node)
              (dom/get-parent anchor-node)
              anchor-node)
            container   (dom/get-parent span-node)]
        (when (= node container)
          [span-node anchor-offset])))))

(defn absolute-offset
  [node child offset]
  (loop [nodes (seq (dom/get-children node))
         acc 0]
    (if-let [head (first nodes)]
      (if (= head child)
        (+ acc offset)
        (recur (rest nodes) (+ acc (.-length (.-textContent head)))))
      nil)))

(defn get-prev-node
  [parent node]
  (->> (d/with-prev (dom/get-children parent))
       (d/seek (fn [[it _]] (= node it)))
       (second)))

;; Component that renders the component content
(mf/defc comment-content*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [content]}]
  (let [comment-elements (mf/use-memo (mf/deps content) #(parse-comment content))]
    (for [[idx {:keys [type content]}] (d/enumerate comment-elements)]
      (case type
        [:span
         {:key idx
          :class (stl/css-case
                  :comment-text (= type :text)
                  :comment-mention (= type :mention))}
         content]))))

;; Input text for comments with mentions
(mf/defc comment-input*
  {::mf/props :obj
   ::mf/private true
   ::mf/wrap-props false}
  [{:keys [value placeholder max-length autofocus? on-focus on-blur on-change on-esc on-ctrl-enter]}]

  (let [value            (d/nilv value "")
        prev-value       (h/use-previous value)

        local-ref        (mf/use-ref nil)
        mentions-str     (mf/use-ctx mentions-context)
        cur-mention      (mf/use-var nil)

        prev-selection   (mf/use-var nil)

        init-input
        (mf/use-callback
         (fn [node]
           (mf/set-ref-val! local-ref node)
           (when node
             (doseq [{:keys [type content data]} (parse-comment value)]
               (case type
                 :text     (dom/append-child! node (create-text-node content))
                 :mention  (dom/append-child! node (create-mention-node (:id data) content))
                 nil)))))

        handle-input
        (mf/use-callback
         (mf/deps on-change)
         (fn []
           (let [node     (mf/ref-val local-ref)
                 children (dom/get-children node)]

             (doseq [child-node children]
               ;; Remove nodes that are not span. This can happen if the user copy/pastes
               (when (not= (.-tagName child-node) "SPAN")
                 (.remove child-node))

               ;; If a node is empty we set the content to "empty"
               (when (and (= (dom/get-data child-node "type") "text")
                          (empty? (dom/get-text child-node)))
                 (dom/set-html! child-node "&#8203;"))

               ;; Remove mentions that have been modified
               (when (and (= (dom/get-data child-node "type") "mention")
                          (not= (dom/get-data child-node "fullname")
                                (dom/get-text child-node)))
                 (.remove child-node)))

             ;; If there are no nodes we need to create an empty node
             (when (= 0 (.-length children))
               (dom/append-child! node (create-text-node)))

             (let [new-input (parse-nodes node)]
               (when (and on-change (<= (count new-input) max-length))
                 (on-change new-input))))))

        handle-select
        (mf/use-callback
         (fn []
           (let [node          (mf/ref-val local-ref)
                 selection     (wapi/get-selection)
                 range         (wapi/get-range selection 0)
                 anchor-node   (wapi/range-start-container range)]
             (when (and (= node anchor-node) (.-collapsed range))
               (wapi/set-cursor-after! anchor-node)))

           (let [node (mf/ref-val local-ref)
                 [span-node offset] (current-text-node node)
                 [prev-span prev-offset] @prev-selection]

             (reset! prev-selection #js [span-node offset])

             (when (= (dom/get-data span-node "type") "mention")
               (let [from-offset (absolute-offset node prev-span prev-offset)
                     to-offset (absolute-offset node span-node offset)

                     [_ prev next]
                     (->> node
                          (dom/seq-nodes)
                          (d/with-prev-next)
                          (filter (fn [[elem _ _]] (= elem span-node)))
                          (first))]

                 (if (> from-offset to-offset)
                   (wapi/set-cursor-after! prev)
                   (wapi/set-cursor-before! next))))

             (when span-node
               (let [node-text (subs (dom/get-text span-node) 0 offset)

                     current-at-symbol
                     (str/last-index-of (subs node-text 0 offset) "@")

                     mention-text
                     (subs node-text current-at-symbol)]

                 (if (re-matches #"@\w*" mention-text)
                   (do
                     (reset! cur-mention mention-text)
                     (rx/push! mentions-str {:type :display-mentions})
                     (let [mention (subs mention-text 1)]
                       (when (d/not-empty? mention)
                         (rx/push! mentions-str {:type :filter-mentions :data mention}))))
                   (do
                     (reset! cur-mention nil)
                     (rx/push! mentions-str {:type :hide-mentions}))))))))

        handle-focus
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/set-css-property! (mf/ref-val local-ref) "--placeholder" "")
           (when on-focus
             (on-focus event))))

        handle-blur
        (mf/use-callback
         (mf/deps value)
         (fn [event]
           (when (empty? value)
             (let [node (mf/ref-val local-ref)]
               (dom/set-css-property! node "--placeholder" (dm/str "\"" placeholder "\""))))

           (when on-blur
             (on-blur event))))

        handle-insert-mention
        (fn [data]
          (let [node (mf/ref-val local-ref)
                [span-node offset] (current-text-node node)]
            (when span-node
              (let [node-text
                    (dom/get-text span-node)

                    current-at-symbol
                    (or (str/last-index-of (subs node-text 0 offset) "@")
                        (absolute-offset node span-node offset))

                    mention
                    (re-find #"@\w*" (subs node-text current-at-symbol))

                    prefix
                    (subs node-text 0 current-at-symbol)

                    suffix
                    (subs node-text (+ current-at-symbol (count mention)))

                    mention-span (create-mention-node (-> data :user :id) (-> data :user :fullname))
                    after-span (create-text-node (dm/str " " suffix))
                    sel (wapi/get-selection)]

                (dom/set-html! span-node (if (empty? prefix) "&#8203;" prefix))
                (dom/insert-after! node span-node mention-span)
                (dom/insert-after! node mention-span after-span)
                (wapi/set-cursor-after! after-span)
                (wapi/collapse-end! sel)

                (when on-change
                  (on-change (parse-nodes node)))))))

        handle-key-down
        (mf/use-fn
         (mf/deps on-esc on-ctrl-enter handle-select handle-input)
         (fn [event]
           (handle-select event)

           (let [node (mf/ref-val local-ref)
                 [span-node offset] (current-text-node node)]

             (cond
               (and @cur-mention (kbd/enter? event))
               (do (dom/prevent-default event)
                   (dom/stop-propagation event)
                   (rx/push! mentions-str {:type :insert-selected-mention}))

               (and @cur-mention (kbd/down-arrow? event))
               (do (dom/prevent-default event)
                   (dom/stop-propagation event)
                   (rx/push! mentions-str {:type :insert-next-mention}))

               (and @cur-mention (kbd/up-arrow? event))
               (do (dom/prevent-default event)
                   (dom/stop-propagation event)
                   (rx/push! mentions-str {:type :insert-prev-mention}))

               (and @cur-mention (kbd/esc? event))
               (do (dom/prevent-default event)
                   (dom/stop-propagation event)
                   (rx/push! mentions-str {:type :hide-mentions}))

               (and (kbd/esc? event) (fn? on-esc))
               (on-esc event)

               (and (kbd/mod? event) (kbd/enter? event) (fn? on-ctrl-enter))
               (on-ctrl-enter event)

               (kbd/enter? event)
               (let [sel (wapi/get-selection)
                     range (.getRangeAt sel 0)]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (let [[span-node offset] (current-text-node node)]
                   (.deleteContents range)
                   (handle-input)

                   (when span-node
                     (let [txt (.-textContent span-node)]
                       (dom/set-html! span-node (dm/str (subs txt 0 offset) "\n&#8203;" (subs txt offset)))
                       (wapi/set-cursor! span-node (inc offset))
                       (handle-input)))))

               (kbd/backspace? event)
               (let [prev-node (get-prev-node node span-node)]
                 (when (and (some? prev-node)
                            (= "mention" (dom/get-data prev-node "type"))
                            (= offset 1))
                   (dom/prevent-default event)
                   (dom/stop-propagation event)
                   (.remove prev-node)))))))]

    (mf/use-layout-effect
     (mf/deps autofocus?)
     (fn []
       (when autofocus?
         (dom/focus! (mf/ref-val local-ref)))))

    ;; Creates the handlers for selection
    (mf/use-effect
     (mf/deps handle-select)
     (fn []
       (let [handle-select* handle-select]
         (js/document.addEventListener "selectionchange" handle-select*)
         #(js/document.removeEventListener "selectionchange" handle-select*))))

    ;; Effect to communicate with the mentions panel
    (mf/use-effect
     (fn []
       (when mentions-str
         (->> mentions-str
              (rx/subs!
               (fn [{:keys [type data]}]
                 (case type
                   :insert-mention
                   (handle-insert-mention data)

                   nil)))))))

    ;; Auto resize input to display the comment
    (mf/use-layout-effect
     nil
     (fn []
       (let [node (mf/ref-val local-ref)]
         (set! (.-height (.-style node)) "0")
         (set! (.-height (.-style node)) (str (+ 2 (.-scrollHeight node)) "px")))))

    (mf/use-effect
     (mf/deps value prev-value)
     (fn []
       (let [node (mf/ref-val local-ref)]
         (cond
           (and (d/not-empty? prev-value) (empty? value))
           (do (dom/set-html! node "")
               (dom/append-child! node (create-text-node))
               (dom/set-css-property! node "--placeholder" "")
               (dom/focus! node))

           (and (some? node) (empty? value) (not (dom/focus? node)))
           (dom/set-css-property! node "--placeholder" (dm/str "\"" placeholder "\""))

           (some? node)
           (dom/set-css-property! node "--placeholder" "")))))

    [:div
     {:role "textbox"
      :class (stl/css :comment-input)
      :content-editable "true"
      :suppress-content-editable-warning true
      :on-input handle-input
      :ref init-input
      :on-key-down handle-key-down
      :on-focus handle-focus
      :on-blur handle-blur}]))

(mf/defc mentions-panel*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [profiles]}]

  (let [mentions-str (mf/use-ctx mentions-context)

        profile (mf/deref refs/profile)

        mention-state
        (mf/use-state {:display? false
                       :mention-filter ""
                       :selected 0})

        {:keys [display? mention-filter selected]} @mention-state

        mentions-users
        (mf/use-memo
         (mf/deps mention-filter)
         #(->> (vals profiles)
               (filter
                (fn [{:keys [id fullname email]}]
                  (and
                   (not= id (:id profile))
                   (or (not mention-filter)
                       (empty? mention-filter)
                       (str/includes? (str/lower fullname) (str/lower mention-filter))
                       (str/includes? (str/lower email) (str/lower mention-filter))))))
               (take 4)
               (into [])))

        selected (mth/clamp selected 0 (dec (count mentions-users)))

        handle-click-mention
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "user-id")
                        (uuid/uuid))]
             (rx/push! mentions-str {:type :insert-mention
                                     :data {:user (get profiles id)}}))))]

    (mf/use-effect
     (mf/deps mentions-users selected)
     (fn []
       (let [sub
             (->> mentions-str
                  (rx/subs!
                   (fn [{:keys [type data]}]
                     (case type
                       ;; Display the mentions dialog
                       :display-mentions
                       (swap! mention-state assoc :display? true)

                       ;; Hide mentions
                       :hide-mentions
                       (swap! mention-state assoc :display? false :mention-filter "")

                       ;; Filter the metions by some characters
                       :filter-mentions
                       (swap! mention-state assoc :mention-filter data)

                       :insert-selected-mention
                       (rx/push! mentions-str {:type :insert-mention
                                               :data {:user (get mentions-users selected)}})

                       :insert-next-mention
                       (swap! mention-state update :selected #(mth/clamp (inc %) 0 (dec (count mentions-users))))

                       :insert-prev-mention
                       (swap! mention-state update :selected #(mth/clamp (dec %) 0 (dec (count mentions-users))))

                       ;;
                       nil))))]
         #(rx/dispose! sub))))

    (when display?
      [:div {:class (stl/css :comments-mentions-choice)}
       (if (empty? mentions-users)
         [:div {:class (stl/css :comments-mentions-empty)}
          (tr "comments.mentions.not-found" mention-filter)]

         (for [[idx {:keys [id fullname email] :as user}] (d/enumerate mentions-users)]
           [:div {:key id
                  :on-pointer-down handle-click-mention
                  :data-user-id (dm/str id)
                  :class (stl/css-case :comments-mentions-entry true
                                       :is-selected (= selected idx))}
            [:img {:class (stl/css :comments-mentions-avatar)
                   :src (cfg/resolve-profile-photo-url user)}]
            [:div {:class (stl/css :comments-mentions-name)} fullname]
            [:div {:class (stl/css :comments-mentions-email)} email]]))])))

(mf/defc mentions-button*
  {::mf/props :obj
   ::mf/private true}
  []
  (let [mentions-str      (mf/use-ctx mentions-context)
        display-mentions* (mf/use-state false)

        handle-mouse-down
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (rx/push! mentions-str {:type :display-mentions})))]

    (mf/use-effect
     (fn []
       (let [sub
             (rx/subs!
              (fn [{:keys [type _]}]
                (case type
                  :display-mentions (reset! display-mentions* true)
                  :hide-mentions    (reset! display-mentions* false)
                  nil))
              mentions-str)]
         #(rx/dispose! sub))))

    [:> icon-button*
     {:variant "ghost"
      :aria-label (tr "labels.options")
      :on-pointer-down handle-mouse-down
      :icon-class (stl/css-case :open-mentions-button true
                                :is-toggled @display-mentions*)
      :icon "at"}]))

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
     [:div {:class (stl/css-case :avatar-mask true
                                 :avatar-darken (= variant "solved"))}]]))

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
    [:div {:class (stl/css :author-identity)}
     [:div {:class (stl/css :author-fullname)} (:fullname profile)]
     [:div {:class (stl/css :author-timeago)} (dt/timeago (:modified-at item))]]]

   [:div {:class (stl/css :item)}
    [:> comment-content* {:content (:content item)}]]

   [:div {:class (stl/css :replies)}
    (let [total-comments (:count-comments item 1)
          total-replies  (dec total-comments)
          unread-replies (:count-unread-comments item 0)]
      [:*
       (when (> total-replies 0)
         (if (= total-replies 1)
           [:span {:class (stl/css :replies-total)} (str total-replies " " (tr "labels.reply"))]
           [:span {:class (stl/css :replies-total)} (str total-replies " " (tr "labels.replies"))]))

       (when (and (> total-replies 0) (> unread-replies 0))
         (if (= unread-replies 1)
           [:span {:class (stl/css :replies-unread)} (str unread-replies " " (tr "labels.reply.new"))]
           [:span {:class (stl/css :replies-unread)} (str unread-replies " " (tr "labels.replies.new"))]))])]])

(mf/defc comment-form-buttons*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-submit on-cancel is-disabled]}]
  (let [handle-cancel
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [event]
           (when (kbd/enter? event)
             (on-cancel))))

        handle-submit
        (mf/use-fn
         (mf/deps on-submit)
         (fn [event]
           (when (kbd/enter? event)
             (on-submit))))]

    [:div {:class (stl/css :form-buttons-wrapper)}
     [:> mentions-button*]
     [:> button* {:variant "ghost"
                  :type "button"
                  :on-key-down handle-cancel
                  :on-click on-cancel}
      (tr "ds.confirm-cancel")]
     [:> button* {:variant "primary"
                  :type "button"
                  :on-key-down handle-submit
                  :on-click on-submit
                  :disabled is-disabled}
      (tr "labels.post")]]))

(mf/defc comment-reply-form*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-submit]}]
  (let [show-buttons? (mf/use-state false)
        content       (mf/use-state "")

        disabled?     (str/blank? @content)

        handle-focus
        (mf/use-fn
         #(reset! show-buttons? true))

        handle-blur
        (mf/use-fn
         #(reset! show-buttons? false))

        handle-change
        (mf/use-fn
         #(reset! content %))

        handle-cancel
        (mf/use-fn
         (fn []
           (reset! content "")
           (reset! show-buttons? false)))

        handle-submit
        (mf/use-fn
         (mf/deps @content)
         (fn []
           (on-submit @content)
           (handle-cancel)))]

    [:div {:class (stl/css :form)}
     [:> comment-input*
      {:value @content
       :placeholder (tr "labels.reply.thread")
       :autofocus? true
       :on-blur handle-blur
       :on-focus handle-focus
       :on-ctrl-enter handle-submit
       :on-change handle-change
       :max-length 750}]
     (when (or @show-buttons? (seq @content))
       [:> comment-form-buttons* {:on-submit handle-submit
                                  :on-cancel handle-cancel
                                  :is-disabled disabled?}])]))

(mf/defc comment-edit-form*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [content on-submit on-cancel]}]
  (let [content (mf/use-state content)

        disabled? (str/blank? @content)

        handle-change
        (mf/use-fn
         #(reset! content %))

        handle-submit
        (mf/use-fn
         (mf/deps @content)
         (fn []
           (on-submit @content)))]

    [:div {:class (stl/css :form)}
     [:> comment-input*
      {:value @content
       :autofocus? true
       :on-ctrl-enter handle-submit
       :on-change handle-change
       :max-length 750}]
     [:> comment-form-buttons* {:on-submit handle-submit
                                :on-cancel on-cancel
                                :is-disabled disabled?}]]))

(mf/defc comment-floating-thread-draft*
  {::mf/props :obj}
  [{:keys [draft zoom on-cancel on-submit position-modifier profiles]}]
  (let [profile   (mf/deref refs/profile)

        mentions-str (mf/use-memo #(rx/subject))

        position  (cond-> (:position draft)
                    (some? position-modifier)
                    (gpt/transform position-modifier))
        content   (:content draft)

        pos-x     (* (:x position) zoom)
        pos-y     (* (:y position) zoom)

        disabled? (str/blank? @content)

        handle-esc
        (mf/use-fn
         (mf/deps draft)
         (fn [event]
           (dom/stop-propagation event)
           (if (fn? on-cancel)
             (on-cancel)
             (st/emit! :interrupt))))

        handle-change
        (mf/use-fn
         (mf/deps draft)
         (fn [content]
           (st/emit! (dcm/update-draft-thread {:content content}))))

        handle-submit
        (mf/use-fn
         (mf/deps draft)
         (fn []
           (on-submit draft)))]

    [:> (mf/provider mentions-context) {:value mentions-str}
     [:div
      {:class (stl/css :floating-preview-wrapper)
       :data-testid "floating-thread-bubble"
       :style {:top (str pos-y "px")
               :left (str pos-x "px")}
       :on-click dom/stop-propagation}
      [:> comment-avatar* {:class (stl/css :avatar-lg)
                           :image (cfg/resolve-profile-photo-url profile)}]]
     [:div {:class (stl/css :floating-thread-wrapper)
            :style {:top (str (- pos-y 24) "px")
                    :left (str (+ pos-x 28) "px")}
            :on-click dom/stop-propagation}
      [:div {:class (stl/css :form)}
       [:> comment-input*
        {:placeholder (tr "labels.write-new-comment")
         :value (or content "")
         :autofocus? true
         :on-esc handle-esc
         :on-change handle-change
         :on-ctrl-enter handle-submit
         :max-length 750}]
       [:> comment-form-buttons* {:on-submit handle-submit
                                  :on-cancel on-cancel
                                  :is-disabled disabled?}]]
      [:> mentions-panel* {:profiles profiles}]]]))

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
     [:div {:class (stl/css :floating-thread-header-left)}
      (tr "labels.comment") " " [:span {:class (stl/css :grayed-text)} "#" (:seqn thread)]]
     [:div {:class (stl/css :floating-thread-header-right)}
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

    [:div {:class (stl/css :floating-thread-item-wrapper)}
     [:div {:class (stl/css :floating-thread-item)}
      [:div {:class (stl/css :author)}
       [:> comment-avatar* {:image (cfg/resolve-profile-photo-url owner)}]
       [:div {:class (stl/css :author-identity)}
        [:div {:class (stl/css :author-fullname)} (:fullname owner)]
        [:div {:class (stl/css :author-timeago)} (dt/timeago (:modified-at comment))]]

       (when (= (:id profile) (:id owner))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :on-click on-toggle-options
                           :icon "menu"}])]

      [:div {:class (stl/css :item)}
       (if @edition?
         [:> comment-edit-form* {:content (:content comment)
                                 :on-submit on-submit
                                 :on-cancel on-cancel}]
         [:span {:class (stl/css :text)}
          [:> comment-content* {:content (:content comment)}]])]]

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
  (let [ref           (mf/use-ref)
        mentions-str (mf/use-memo #(rx/subject))
        thread-id     (:id thread)
        thread-pos    (:position thread)

        base-pos      (cond-> thread-pos
                        (some? position-modifier)
                        (gpt/transform position-modifier))

        max-height    (when (some? viewport) (int (* (:height viewport) 0.5)))

        ;; We should probably look for a better way of doing this.
        bubble-margin {:x 24 :y 24}
        pos           (offset-position base-pos viewport zoom bubble-margin)

        margin-x      (* (:x bubble-margin) (if (= (:h-dir pos) :left) -1 1))
        margin-y      (* (:y bubble-margin) (if (= (:v-dir pos) :top) -1 1))
        pos-x         (+ (* (:x pos) zoom) margin-x)
        pos-y         (- (* (:y pos) zoom) margin-y)

        comments-ref  (mf/with-memo [thread-id]
                        (make-comments-ref thread-id))
        comments-map  (mf/deref comments-ref)

        comments      (mf/with-memo [comments-map]
                        (->> (vals comments-map)
                             (sort-by :created-at)))

        first-comment (first comments)

        on-submit
        (mf/use-fn
         (mf/deps thread)
         (fn [content]
           (st/emit! (dcm/add-comment thread content))))]

    (mf/with-effect [thread-id]
      (st/emit! (dcm/retrieve-comments thread-id)))

    (mf/with-effect [thread-id]
      (st/emit! (dcm/update-comment-thread-status thread-id)))

    (mf/with-layout-effect [thread-pos comments-map]
      (when-let [node (mf/ref-val ref)]
        (dom/scroll-into-view-if-needed! node)))

    [:& (mf/provider mentions-context) {:value mentions-str}
     (when (some? first-comment)
       [:div {:class (stl/css-case :floating-thread-wrapper true
                                   :left (= (:h-dir pos) :left)
                                   :top (= (:v-dir pos) :top))
              :id (str "thread-" thread-id)
              :style {:left (str pos-x "px")
                      :top (str pos-y "px")
                      "--comment-height" (str max-height "px")}
              :on-click dom/stop-propagation}

        [:div {:class (stl/css :floating-thread-header)}
         [:> comment-floating-thread-header* {:profiles profiles
                                              :thread thread
                                              :origin origin}]]

        [:div {:class (stl/css :floating-thread-main)}
         [:> comment-floating-thread-item* {:comment first-comment
                                            :profiles profiles
                                            :thread thread}]
         (for [item (rest comments)]
           [:* {:key (dm/str (:id item))}
            [:> comment-floating-thread-item* {:comment item
                                               :profiles profiles}]])]

        [:> comment-reply-form* {:on-submit on-submit}]

        [:> mentions-panel* {:profiles profiles}]])]))

(mf/defc comment-floating-bubble*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [thread profiles zoom is-open on-click origin position-modifier]}]
  (let [owner        (get profiles (:owner-id thread))

        base-pos     (cond-> (:position thread)
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

        pos-x        (floor (* (or (:new-position-x @state) (:x base-pos)) zoom))
        pos-y        (floor (* (or (:new-position-y @state) (:y base-pos)) zoom))

        on-pointer-down
        (mf/use-fn
         (mf/deps origin was-open? is-open drag?)
         (fn [event]
           (when (not= origin :viewer)
             (swap! state assoc :grabbing? true)
             (mf/set-ref-val! was-open? is-open)
             (when is-open (st/emit! (dcm/close-thread)))
             (mf/set-ref-val! drag? false)
             (dom/stop-propagation event)
             (dom/capture-pointer event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-ref (dom/get-client-position event)))))

        on-pointer-up
        (mf/use-fn
         (mf/deps origin thread (select-keys @state [:new-position-x :new-position-y :new-frame-id]))
         (fn [event]
           (when (not= origin :viewer)
             (swap! state assoc :grabbing? false)
             (dom/stop-propagation event)
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-ref nil)
             (when (and
                    (some? (:new-position-x @state))
                    (some? (:new-position-y @state)))
               (st/emit! (dwcm/update-comment-thread-position thread [(:new-position-x @state)
                                                                      (:new-position-y @state)]))
               (swap! state assoc
                      :new-position-x nil
                      :new-position-y nil)))))

        on-pointer-move
        (mf/use-fn
         (mf/deps origin drag? position zoom)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! drag? true)
             (dom/stop-propagation event)
             (when-let [_ (mf/ref-val dragging-ref)]
               (let [start-pt   (mf/ref-val start-ref)
                     current-pt (dom/get-client-position event)
                     delta-x    (/ (- (:x current-pt) (:x start-pt)) zoom)
                     delta-y    (/ (- (:y current-pt) (:y start-pt)) zoom)]
                 (swap! state assoc
                        :new-position-x (+ (:x position) delta-x)
                        :new-position-y (+ (:y position) delta-y)))))))

        on-pointer-enter
        (mf/use-fn
         (mf/deps is-open)
         (fn [event]
           (dom/stop-propagation event)
           (when (false? is-open)
             (swap! state assoc :hover? true))))

        on-pointer-leave
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! state assoc :hover? false)))

        on-click*
        (mf/use-fn
         (mf/deps origin thread on-click was-open? drag? (select-keys @state [:hover?]))
         (fn [event]
           (dom/stop-propagation event)
           (when (or (and (mf/ref-val was-open?) (mf/ref-val drag?))
                     (and (not (mf/ref-val was-open?)) (not (mf/ref-val drag?))))
             (swap! state assoc :hover? false)
             (st/emit! (dcm/open-thread thread)))
           (when (= origin :viewer)
             (on-click thread))))]

    [:div {:style {:top (str pos-y "px")
                   :left (str pos-x "px")}
           :on-pointer-down on-pointer-down
           :on-pointer-up on-pointer-up
           :on-pointer-move on-pointer-move
           :on-pointer-enter on-pointer-enter
           :on-pointer-leave on-pointer-leave
           :on-click on-click*
           :class (stl/css-case :floating-preview-wrapper true
                                :floating-preview-bubble (false? (:hover? @state))
                                :grabbing (true? (:grabbing? @state)))}

     (if (:hover? @state)
       [:div {:class (stl/css :floating-thread-wrapper :floating-preview-displacement)}
        [:div {:class (stl/css :floating-thread-item-wrapper)}
         [:div {:class (stl/css :floating-thread-item)}
          [:> comment-info* {:item thread
                             :profile owner}]]]]

       [:> comment-avatar* {:image (cfg/resolve-profile-photo-url owner)
                            :class (stl/css :avatar-lg)
                            :data-testid (str "floating-thread-bubble-" (:seqn thread))
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

    [:div {:class (stl/css :cover)
           :on-click on-click*}
     [:div {:class (stl/css :location)}
      [:div {:class (stl/css :location-text)}
       (str "#" (:seqn item))
       (str " - " (:page-name item))
       (when (and (some? frame) (not (cfh/root? frame)))
         (str " - " (:name frame)))]]

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

    [:div {:class (stl/css :cover)
           :on-click on-click*}
     [:div {:class (stl/css :location)}
      [:> icon* {:icon-id "comments"
                 :class (stl/css :location-icon)}]
      [:div {:class (stl/css :location-text)}
       (str "#" (:seqn item))
       (str " " (:file-name item))
       (str ", " (:page-name item))]]

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
