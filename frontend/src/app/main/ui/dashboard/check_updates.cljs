;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.dashboard.check-updates
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.version :as v]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private telemetry-origin
  "check-updates-modal")

(def ^:private changelog-md-url
  "https://raw.githubusercontent.com/penpot/penpot/refs/heads/staging/CHANGES.md")

(def ^:private changelog-url
  "https://github.com/penpot/penpot/blob/main/CHANGES.md")

(def ^:private release-notes-url
  "https://penpot.app/release-notes")

(def ^:private version-heading-re
  #"(?m)^##\s+(\d+\.\d+\.\d+)(.*)$")

(def ^:private bullet-re
  #"^- (.+)$")

(def ^:private rocket-heading-re
  #"(?m)^### :rocket: Epics and highlights\s*$")

(defn- unreleased-suffix?
  [suffix]
  (str/includes? (str/lower (or suffix "")) "unreleased"))

(defn- parse-section-items
  [section]
  (->> (cstr/split-lines section)
       (keep (fn [line]
               (when-let [[_ item] (re-matches bullet-re (str/trim line))]
                 item)))
       vec))

(defn- extract-rocket-items
  "Given a version section body, find the :rocket: subsection and
  extract its bullet items. Returns nil if no :rocket: or empty."
  [version-body]
  (when-let [[_ rocket-body] (str/split version-body rocket-heading-re 2)]
    (let [subsection (-> (str/split rocket-body #"(?m)(?=^#{2,3}\s)") first)]
      (when subsection
        (let [items (parse-section-items subsection)]
          (when (seq items) items))))))

(def ^:private inline-md-re
  #"\[([^\]]+)\]\(((?:\([^)]*\)|[^)\s])*)\)|\*\*([^*]+)\*\*")

(defn- http-url?
  [url]
  (boolean (re-matches #"https?://.*" (or url ""))))

(defn parse-highlight-item
  "Parse one highlight string with inline markdown (links, bold) into a
  vector of {:type :text/:link/:bold, :text ..., :href ...} descriptors.
  Anything unrecognized degrades to :text. Total over strings; nil and
  empty input return []."
  [text]
  (if (or (not (string? text)) (= "" text))
    []
    (loop [out [] s text]
      (if (= "" s)
        out
        (if-let [[m link-text link-href bold-text] (re-find inline-md-re s)]
          (let [idx    (cstr/index-of s m)
                before (subs s 0 idx)
                after  (subs s (+ idx (count m)))
                out    (if (= "" before) out (conj out {:type :text :text before}))]
            (cond
              (some? link-text)
              (if (http-url? link-href)
                (recur (conj out {:type :link :text link-text :href link-href}) after)
                (recur (conj out {:type :text :text m}) after))

              :else
              (recur (conj out {:type :bold :text bold-text}) after)))
          (conj out {:type :text :text s}))))))

(defn parse-highlights
  "Parse CHANGES.md into released version sections with bullet items from
  the :rocket: Epics and highlights subsection. Skips Unreleased headings,
  versions without a :rocket: section, and versions with an empty one.
  Preserves file order (newest first)."
  [markdown]
  (if-not (string? markdown)
    []
    (->> (str/split markdown #"(?m)(?=^##\s+\d+\.\d+\.\d+)")
         (keep (fn [part]
                 (when-let [[_ version suffix] (re-find version-heading-re part)]
                   (when-not (unreleased-suffix? suffix)
                     (when-let [items (extract-rocket-items part)]
                       {:version version
                        :items   items})))))
         vec)))

(defn parse-latest-released-version
  "Return the first non-unreleased `## X.Y.Z` heading from a CHANGES.md body."
  [markdown]
  (when (string? markdown)
    (some->> (re-seq version-heading-re markdown)
             (keep (fn [[_ version suffix]]
                     (when-not (unreleased-suffix? suffix) version)))
             first)))

(defn highlights-until-installed
  "Keep released sections newer than the installed version (major, minor,
  patch). Stops at the installed version or any older section."
  [highlights installed]
  (into []
        (take-while #(v/newer? (:version %) installed))
        highlights))

(defn- show-available-dialog
  [{:keys [installed latest highlights]}]
  (st/emit! (modal/show {:type :check-updates-available
                         :installed installed
                         :latest latest
                         :highlights highlights})))

(defn- show-uptodate-dialog
  [version]
  (st/emit! (modal/show {:type :check-updates-uptodate
                         :version version})))

(defn- show-unable-dialog
  []
  (st/emit! (modal/show {:type :check-updates-unable})))

(defn- handle-highlights
  [installed body]
  (let [latest   (parse-latest-released-version body)
        sections (parse-highlights body)]
    (cond
      (nil? latest)
      (show-unable-dialog)

      (not (v/newer? latest installed))
      (show-uptodate-dialog installed)

      :else
      (show-available-dialog
       {:installed  installed
        :latest     latest
        :highlights (highlights-until-installed sections installed)}))))

(defn check-for-updates!
  ([current-version]
   (check-for-updates! current-version nil))
  ([current-version {:keys [on-start on-finish]}]
   (when on-start (on-start))
   (->> (http/send! {:method :get
                     :mode :cors
                     :omit-default-headers true
                     :uri changelog-md-url
                     :response-type :text})
        (rx/subs!
         (fn [response]
           (when on-finish (on-finish))
           (if (http/success? response)
             (handle-highlights current-version (:body response))
             (show-unable-dialog)))
         (fn [_cause]
           (when on-finish (on-finish))
           (show-unable-dialog))))))

(mf/defc check-updates-unable-modal*
  {::mf/register modal/components
   ::mf/register-as :check-updates-unable}
  [_]
  (let [on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-try-again
        (mf/use-fn
         (fn []
           (st/emit! (modal/hide))
           (check-for-updates! (:base cf/version))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-title-row)}
        [:> icon* {:icon-id i/msg-neutral
                   :class (stl/css :modal-title-icon)
                   :aria-hidden true}]
        [:> heading* {:level 2
                      :typography "headline-medium"
                      :class (stl/css :modal-title)}
         (tr "dashboard.check-updates.unable-title")]]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close
                         :class (stl/css :modal-close-btn)}]]

      [:div {:class (stl/css :modal-content)}
       [:> text* {:as "p"
                  :typography t/body-large
                  :class (stl/css :modal-msg)}
        (tr "dashboard.check-updates.unable-message")]
       [:> text* {:as "p"
                  :typography t/body-large
                  :class (stl/css :modal-msg)}
        (tr "dashboard.check-updates.unable-hint")]]

      [:div {:class (stl/css :modal-footer)}
       [:> button* {:variant "primary"
                    :on-click on-try-again}
        (tr "dashboard.check-updates.try-again")]]]]))

(mf/defc check-updates-uptodate-modal*
  {::mf/register modal/components
   ::mf/register-as :check-updates-uptodate}
  [{:keys [version]}]
  (let [on-close
        (mf/use-fn #(st/emit! (modal/hide)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-title-row)}
        [:> icon* {:icon-id i/tick
                   :class (stl/css :modal-title-icon)
                   :aria-hidden true}]
        [:> heading* {:level 2
                      :typography "headline-medium"
                      :class (stl/css :modal-title)}
         (tr "dashboard.check-updates.uptodate-title")]]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close
                         :class (stl/css :modal-close-btn)}]]

      [:div {:class (stl/css :modal-content)}
       [:> text* {:as "p"
                  :typography t/body-large
                  :class (stl/css :modal-msg)}
        (tr "dashboard.check-updates.uptodate-message")
        " "
        [:span {:class (stl/css :version)} version]]]

      [:div {:class (stl/css :modal-footer)}
       [:> button* {:variant "secondary"
                    :on-click on-close}
        (tr "labels.close")]]]]))

(mf/defc check-updates-available-modal*
  {::mf/register modal/components
   ::mf/register-as :check-updates-available}
  [{:keys [installed latest highlights]}]
  (let [on-close
        (mf/use-fn #(st/emit! (modal/hide)))

        on-changelog
        (mf/use-fn
         (mf/deps installed)
         (fn []
           (st/emit! (ev/event {::ev/name "explore-changelog-click"
                                ::ev/origin telemetry-origin
                                :version installed}))
           (dom/open-new-window changelog-url)))

        on-release-notes
        (mf/use-fn
         (mf/deps installed)
         (fn []
           (st/emit! (ev/event {::ev/name "explore-product-updates-click"
                                ::ev/origin telemetry-origin
                                :version installed}))
           (dom/open-new-window release-notes-url)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container :modal-container-available)}
      [:div {:class (stl/css :modal-header)}
       [:div {:class (stl/css :modal-title-row)}
        [:> icon* {:icon-id i/info
                   :class (stl/css :modal-title-icon)
                   :aria-hidden true}]
        [:> heading* {:level 2
                      :typography "headline-medium"
                      :class (stl/css :modal-title)}
         (tr "dashboard.check-updates.available-title")]]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close
                         :class (stl/css :modal-close-btn)}]]

      [:div {:class (stl/css :version-bar)}
       [:span {:class (stl/css :version-bar-side)}
        (tr "dashboard.check-updates.installed-version")
        " "
        [:span {:class (stl/css :version)} installed]]
       [:> icon* {:icon-id i/arrow-right
                  :class (stl/css :version-bar-arrow)
                  :size "s"
                  :aria-hidden true}]
       [:span {:class (stl/css :version-bar-side)}
        (tr "dashboard.check-updates.latest-version")
        " "
        [:span {:class (stl/css :version :version-accent)} latest]]]

      [:div {:class (stl/css :modal-content)}
       [:> text* {:as "p"
                  :typography t/body-large
                  :class (stl/css :modal-msg)}
        (tr "dashboard.check-updates.available-message")]

       (when (seq highlights)
         [:*
          [:> text* {:as "h3"
                     :typography t/headline-small
                     :class (stl/css :highlights-title)}
           (tr "dashboard.check-updates.highlights-title")]

          [:div {:class (stl/css :highlights-scroll)}
           (for [section highlights]
             (let [version (:version section)
                   items   (:items section)]
               [:div {:key version
                      :class (stl/css :highlights-section)}
                [:div {:class (stl/css :highlights-version)} version]
                [:ul {:class (stl/css :highlights-list)}
                 (for [item items]
                   [:li {:key item
                         :class (stl/css :highlights-item)}
                    (for [[idx frag] (map-indexed vector (parse-highlight-item item))]
                      (case (:type frag)
                        :link
                        [:a {:key idx
                             :href (:href frag)
                             :target "_blank"
                             :rel "noopener noreferrer"}
                         (:text frag)]

                        :bold
                        [:strong {:key idx} (:text frag)]

                        (:text frag)))])]]))]])]

      [:div {:class (stl/css :modal-footer :modal-footer-available)}
       [:> button* {:variant "secondary"
                    :on-click on-changelog}
        (tr "dashboard.check-updates.view-changelog")]
       [:> button* {:variant "primary"
                    :on-click on-release-notes}
        (tr "dashboard.check-updates.view-release-notes")]]]]))
