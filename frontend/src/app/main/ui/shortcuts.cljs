;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.shortcuts.customize :as customize]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def ^:private modifier-keys
  #{"Control" "Shift" "Alt" "Meta"})

(def ^:private key-name-map
  {"ArrowUp"    "up"
   "ArrowDown"  "down"
   "ArrowLeft"  "left"
   "ArrowRight" "right"
   "Escape"     "escape"
   "Enter"      "enter"
   "Backspace"  "backspace"
   "Delete"     "del"
   "Tab"        "tab"
   " "          "space"})

(defn- keyboard-event->mousetrap
  [^js event]
  (let [parts (cond-> []
                (and (.-ctrlKey event)
                     (not (cf/check-platform? :macos)))
                (conj "ctrl")

                (and (.-metaKey event)
                     (cf/check-platform? :macos))
                (conj "command")

                (.-altKey event)
                (conj "alt")

                (.-shiftKey event)
                (conj "shift"))
        key   (.-key event)
        key   (if (contains? modifier-keys key)
                nil
                (or (get key-name-map key)
                    (.toLowerCase key)))]
    (when key
      (str/join "+" (conj parts key)))))

(defn- keyboard-event->display-parts
  [^js event]
  (let [parts (cond-> []
                (and (.-ctrlKey event)
                     (not (cf/check-platform? :macos)))
                (conj "ctrl")

                (and (.-metaKey event)
                     (cf/check-platform? :macos))
                (conj "command")

                (.-altKey event)
                (conj "alt")

                (.-shiftKey event)
                (conj "shift"))
        key   (.-key event)]
    (if (contains? modifier-keys key)
      {:modifiers parts :finalized? false}
      {:modifiers parts
       :final-key (or (get key-name-map key) (.toLowerCase key))
       :finalized? true})))

(defn translation-keyname
  [type keyname]
  (let [translat-pre (case type
                       :sc      "shortcuts."
                       :sec     "shortcut-section."
                       :sub-sec "shortcut-subsection.")]
    (tr (str translat-pre (d/name keyname)))))

(defn- find-conflict
  [new-command all-shortcuts current-key]
  (let [command-index (ds/build-command-index all-shortcuts)]
    (when-let [conflicting-key (get command-index new-command)]
      (when (not= conflicting-key current-key)
        {:key  conflicting-key
         :name (translation-keyname :sc conflicting-key)}))))

(defn import-custom-shortcuts
  [shortcuts all-shortcuts]
  (let [current-customs (get-in shortcuts
                                [:profile :props :custom-shortcuts]
                                {})

        new-customs
        (reduce
         (fn [customs [command recorded-command]]
           (let [conflict (find-conflict recorded-command
                                         all-shortcuts
                                         command)]
             (cond-> (assoc customs command recorded-command)
               (:key conflict)
               (assoc (:key conflict) ""))))
         current-customs
         shortcuts)]

    (du/update-profile-props {:custom-shortcuts new-customs})))

(defn add-translation
  [type item]
  (map (fn [[k v]] [k (assoc v :translation (translation-keyname type k))]) item))

(defn shortcuts->subsections
  [shortcuts]
  (let [subsections (into #{} (mapcat :subsections) (vals shortcuts))
        get-sc-by-subsection
        (fn [subsection [k v]]
          (when (some #(= subsection %) (:subsections v)) {k v}))
        reduce-sc
        (fn [acc subsection]
          (let [shortcuts-by-subsection (into {} (keep (partial get-sc-by-subsection subsection) shortcuts))]
            (assoc acc subsection {:children shortcuts-by-subsection})))]
    (reduce reduce-sc {} subsections)))

(defn build-all-shortcuts
  [workspace-sc dashboard-sc viewer-sc]
  (let [walk (fn walk [element parent-id]
               (if (nil? element)
                 element
                 (let [rec-fn (fn [index [k item]]
                                (let [item-id (if (nil? parent-id)
                                                [index]
                                                (conj parent-id index))]
                                  [k (assoc item :id item-id :children (walk (:children item) item-id))]))]
                   (into {} (map-indexed (partial rec-fn) element)))))
        ws-subs (->> (shortcuts->subsections workspace-sc)
                     (add-translation :sub-sec)
                     (into {}))
        db-subs (->> (shortcuts->subsections dashboard-sc)
                     (add-translation :sub-sec)
                     (into {}))
        vw-subs (->> (shortcuts->subsections viewer-sc)
                     (add-translation :sub-sec)
                     (into {}))
        basics  (into {} (concat (:children (:basics ws-subs))
                                 (:children (:basics db-subs))
                                 (:children (:basics vw-subs))))
        ws-subs (dissoc ws-subs :basics)
        db-subs (dissoc db-subs :basics)
        vw-subs (dissoc vw-subs :basics)
        all     {:basics    {:id [1]
                             :children {:none {:children basics}}
                             :translation (tr "shortcut-section.basics")}
                 :workspace {:id [2]
                             :children ws-subs
                             :translation (tr "shortcut-section.workspace")}
                 :dashboard {:id [3]
                             :children db-subs
                             :translation (tr "shortcut-section.dashboard")}
                 :viewer    {:id [4]
                             :children vw-subs
                             :translation (tr "shortcut-section.viewer")}}
        all     (walk all nil)
        all-sc-names (map #(translation-keyname :sc %)
                          (concat (keys workspace-sc)
                                  (keys dashboard-sc)
                                  (keys viewer-sc)))
        all-sub-names (map #(translation-keyname :sub-sec %)
                           (concat (keys ws-subs)
                                   (keys db-subs)
                                   (keys vw-subs)))
        all-section-names (map #(translation-keyname :sec %) (keys all))]
    {:all-shortcuts all
     :all-sc-names all-sc-names
     :all-sub-names all-sub-names
     :all-section-names all-section-names}))

(mf/defc converted-chars*
  [{:keys [char command class]}]
  (let [modified-keys {:up    ds/up-arrow
                       :down  ds/down-arrow
                       :left  ds/left-arrow
                       :right ds/right-arrow
                       :plus "+"}
        macos-keys    {:command "\u2318"
                       :option  "\u2325"
                       :alt     "\u2325"
                       :delete  "\u232B"
                       :del     "\u232B"
                       :shift   "\u21E7"
                       :control "\u2303"
                       :esc     "\u238B"
                       :enter   "\u23CE"}
        is-macos?     (cf/check-platform? :macos)
        char          (if (contains? modified-keys (keyword char)) ((keyword char) modified-keys) char)
        char          (if (and is-macos? (contains? macos-keys (keyword char))) ((keyword char) macos-keys) char)
        unique-key    (str (d/name command) "-" char)]
    [:span {:class [class (stl/css :key)]
            :key unique-key} char]))

(mf/defc shortcuts-keys*
  [{:keys [content command is-customized]}]
  (let [managed-list    (if (coll? content)
                          content
                          (conj () content))
        chars-list      (map ds/split-sc managed-list)
        last-element    (last chars-list)
        short-char-list (if (= 1 (count chars-list))
                          chars-list
                          (drop-last chars-list))
        penultimate     (last short-char-list)]
    [:span {:class (stl/css :keys)}
     (for [chars short-char-list]
       [:* {:key (str/join chars)}
        (for [char chars]
          [:> converted-chars* {:key (dm/str char "-" (name command))
                                :char char
                                :command command
                                :class (when is-customized (stl/css :customized-key))}])
        
        (when (not= chars penultimate) [:span {:class (stl/css :space)} ","])])
     (when (not= last-element penultimate)
       [:*
        [:span {:class (stl/css :space)} (tr "shortcuts.or")]
        (for [char last-element]
          [:> converted-chars* {:key (dm/str char "-" (name command))
                                :char char
                                :command command
                                :class (when is-customized (stl/css :customized-key))}])])]))

(mf/defc shortcut-row-editable*
  [{:keys [elements all-sc-raw custom-shortcuts command-translate]}]
  (let [sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
        [command  comand-info] sc-by-translate
        content                (or (:show-command comand-info) (:command comand-info))
        customized?            (contains? custom-shortcuts command)
        is-editing*            (mf/use-state false)
        is-editing             (deref is-editing*)

        recording-ref          (mf/use-ref nil)

        display-parts*         (mf/use-state nil)
        display-parts          (deref display-parts*)

        recorded-command*      (mf/use-state nil)
        recorded-command       (deref recorded-command*)

        conflict*              (mf/use-state nil)
        conflict               (deref conflict*)

        clean-editing-state
        (fn []
          (reset! is-editing* false)
          (reset! display-parts* nil)
          (reset! recorded-command* nil)
          (reset! conflict* nil))

        on-reset-shortcut
        (mf/use-fn
         (fn [shortcut-key]
           (st/emit! (customize/reset-custom-shortcut shortcut-key))
           (clean-editing-state)))

        start-editing
        (mf/use-fn
         (fn [_]
           (reset! is-editing* true)
           (reset! display-parts* nil)
           (reset! recorded-command* nil)
           (reset! conflict* nil)))

        stop-editing
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (clean-editing-state)))

        save-fn
        (mf/use-fn
         (mf/deps recorded-command conflict command)
         (fn [event]
           (dom/prevent-default event)
           (when recorded-command
             (st/emit! (customize/set-custom-shortcut
                        command
                        recorded-command
                        (:key conflict))))
           (clean-editing-state)))

        on-editable-container-blur
        (mf/use-fn
         (fn [e]
         (when-not (.contains (.-currentTarget e)
                              (.-relatedTarget e))
           (clean-editing-state))))]

    (mf/with-effect [is-editing]
      (when is-editing
        (some-> (mf/ref-val recording-ref) (dom/focus!))))

    (mf/with-effect [is-editing]
      (when is-editing
        (letfn [(on-keydown [^js event]
                  (.preventDefault event)
                  (.stopPropagation event)
                  (let [recorded-command (keyboard-event->mousetrap event)
                        parts   (keyboard-event->display-parts event)]
                    (reset! display-parts* parts)
                    (when recorded-command
                      (reset! recorded-command* recorded-command)
                      (reset! conflict* (find-conflict recorded-command all-sc-raw command)))))]
          (->> (events/listen (mf/ref-val recording-ref) "keydown" on-keydown)
               (partial events/unlistenByKey)))))
    

    [:li {:class (stl/css-case :shortcuts-name-editable true
                               :shortcuts-editing is-editing
                               :customized customized?)
          :key command-translate}
     [:button {:on-click start-editing
               :disabled is-editing
               :class (stl/css-case :shortcut-button-editable true
                                    :shortcut-button-editing is-editing)}
      [:span {:class (stl/css :command-name)}
       command-translate]
      [:div {:class (stl/css :shortcut-actions)}
       [:> shortcuts-keys* {:content content
                            :command command
                            :is-customized customized?}]]]
     (when is-editing
       [:div {:class (stl/css :shortcut-editing)
              :ref recording-ref
              :tab-index 0
              :on-blur on-editable-container-blur}
        [:div {:class (stl/css :shortcut-recording-area)}
         (if (nil? display-parts)
           [:span {:class (stl/css :shortcut-placeholder)}
            (tr "shortcuts.key-combo")]
           [:span {:class (stl/css :shortcut-recorded-keys)}
            (for [mod (:modifiers display-parts)]
              [:span {:class (stl/css-case :key true
                                           :customized-key customized?) :key mod} mod])
            (when (:final-key display-parts)
              [:span {:class (stl/css-case :key true
                                           :customized-key customized?)} (:final-key display-parts)])
            (when-not (:finalized? display-parts)
              [:span {:class (stl/css :recording-ellipsis)} "..."])])]
        (when recorded-command
          (if conflict
            [:> context-notification* {:level :warning :class (stl/css :modal-error-msg)}
             (tr "shortcuts.edit-modal.conflict" (:name conflict))]
            [:> context-notification* {:level :success :class (stl/css :modal-success-msg)}
             (tr "shortcuts.edit-modal.success" command-translate)]))
        [:div {:class (stl/css :edit-buttons)}
         [:div {:class (stl/css :confirmation-buttons)}
          [:> icon-button* {:variant "secondary"
                            :aria-label (tr "labels.cancel")
                            :on-click stop-editing
                            :icon i/close
                            :icon-size "m"}]
          [:> icon-button* {:variant "primary"
                            :aria-label (tr "labels.save")
                            :on-click save-fn
                            :icon i/tick
                            :icon-size "m"}]]
         (when customized?
           [:> icon-button* {:variant "secondary"
                             :aria-label (tr "shortcuts.reset")
                             :on-click (fn [e]
                                         (dom/stop-propagation e)
                                         (on-reset-shortcut command))
                             :icon i/reload
                             :icon-size "m"}])]])]))

(mf/defc shortcut-row*
  [{:keys [elements all-shortcuts all-sc-raw filter-term is-match-section is-match-subsection
           editable? custom-shortcuts on-reset]}]
  (let [shortcut-translations (->> elements vals (map :translation) sort)
        match-shortcut?       (some #(matches-search % @filter-term) shortcut-translations)
        filtered              (if (and (or is-match-section is-match-subsection) (not match-shortcut?))
                                shortcut-translations
                                (filter #(matches-search % @filter-term) shortcut-translations))
        sorted-filtered       (sort filtered)]

    [:ul {:class (stl/css :sub-menu)}
     (for [command-translate sorted-filtered]
        (let [sc-by-translate  (first (filter #(= (:translation (second %)) command-translate) elements))
              [command  comand-info] sc-by-translate
              content                (or (:show-command comand-info) (:command comand-info))
              customized?            (and editable? (contains? custom-shortcuts command))]
         (if editable?
           [:> shortcut-row-editable* {:elements elements
                                       :all-shortcuts all-shortcuts
                                       :custom-shortcuts custom-shortcuts
                                       :on-reset on-reset
                                       :all-sc-raw all-sc-raw
                                       :command-translate command-translate}]
           [:li {:class (stl/css-case :shortcuts-name true
                                      :customized customized?)
                 :key command-translate}
            [:span {:class (stl/css :command-name)}
             command-translate]
            [:div {:class (stl/css :shortcut-actions)}
             [:> shortcuts-keys* {:content content
                                  :command command
                                  :is-customized customized?}]]])))]))

(mf/defc section-title*
  [{:keys [name is-visible is-sub on-click]}]
  [:div {:class (if is-sub
                  (stl/css :subsection-title)
                  (stl/css :section-title))
         :on-click on-click}
   [:> icon* {:icon-id (if is-visible i/arrow-down i/arrow-right)
              :size "s"}]
   [:span {:class (if is-sub
                    (stl/css :subsection-name)
                    (stl/css :section-name))} name]])

(defn- normalize-open-sections
  [open-sections]
  (if (satisfies? cljs.core/IDeref open-sections)
    @open-sections
    open-sections))

(mf/defc shortcut-subsection*
  [{:keys [subsections manage-sections filter-term is-match-section open-sections
           editable? custom-shortcuts all-shortcuts on-reset all-sc-raw]}]
  (let [open-sections (normalize-open-sections open-sections)]
    (if (= :none (first (keys subsections)))
      [:> shortcut-row* {:elements (:children (:none subsections))
                         :all-shortcuts all-shortcuts
                         :filter-term filter-term
                         :is-match-section is-match-section
                         :is-match-subsection true
                         :editable? editable?
                         :custom-shortcuts custom-shortcuts
                         :on-reset on-reset
                         :all-sc-raw all-sc-raw}]

      [:ul {:class (stl/css :subsection-menu)}
       (for [[sub-name sub-info] subsections]
         (let [visible? (some #(= % (:id sub-info)) open-sections)]
           [:li {:key (name sub-name)}
            [:> section-title* {:name (:translation sub-info)
                                :is-visible visible?
                                :is-sub true
                                :on-click (manage-sections (:id sub-info))}]

            [:div {:style {:display (if visible? "initial" "none")}}
             [:> shortcut-row* {:elements (:children sub-info)
                                :all-shortcuts all-shortcuts
                                :filter-term filter-term
                                :is-match-section is-match-section
                                :is-match-subsection true
                                :editable? editable?
                                :all-sc-raw all-sc-raw
                                :custom-shortcuts custom-shortcuts
                                :on-reset on-reset}]]]))])))

(mf/defc shortcut-section*
  [{:keys [section manage-sections open-sections filter-term
           editable? custom-shortcuts all-shortcuts on-reset all-sc-raw]}]
  (let [open-sections (normalize-open-sections open-sections)
        [_ section-info] section
        section-id          (:id section-info)
        section-translation (:translation section-info)
        subsections         (:children section-info)
        visible?            (some #(= % section-id) open-sections)]
    [:div {:class (stl/css :section)}
     [:> section-title* {:name section-translation
                         :is-visible visible?
                         :is-sub false
                         :on-click (manage-sections section-id)}]

     [:div {:style {:display (if visible? "initial" "none")}}
      [:> shortcut-subsection* {:subsections subsections
                                :open-sections open-sections
                                :manage-sections manage-sections
                                :is-match-section false
                                :filter-term filter-term
                                :editable? editable?
                                :custom-shortcuts custom-shortcuts
                                :all-shortcuts all-shortcuts
                                :all-sc-raw all-sc-raw
                                :on-reset on-reset}]]]))
