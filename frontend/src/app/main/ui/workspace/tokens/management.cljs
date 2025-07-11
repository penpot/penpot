(ns app.main.ui.workspace.tokens.management
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.tokens.management.context-menu :refer [token-context-menu]]
   [app.main.ui.workspace.tokens.management.group :refer [token-group*]]
   [app.util.array :as array]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:token-type-open-status
  (l/derived (l/key :open-status-by-type) refs/workspace-tokens))

(defn- remove-keys [m ks]
  (d/removem (comp ks key) m))

(defn- get-sorted-token-groups
  "Separate token-types into groups of `empty` or `filled` depending if
  tokens exist for that type. Sort each group alphabetically (by their type).
  If `:token-units` is not in cf/flags, number tokens are excluded."
  [tokens-by-type]
  (let [token-units? (contains? cf/flags :token-units)
        token-typography-types? (contains? cf/flags :token-typography-types)
        all-types (cond-> dwta/token-properties
                    (not token-units?) (dissoc :number)
                    (not token-typography-types?) (remove-keys ctt/ff-typography-keys))
        all-types (-> all-types keys seq)]
    (loop [empty  #js []
           filled #js []
           types  all-types]
      (if-let [type (first types)]
        (if (not-empty (get tokens-by-type type))
          (recur empty
                 (array/conj! filled type)
                 (rest types))
          (recur (array/conj! empty type)
                 filled
                 (rest types)))
        [(seq (array/sort! empty))
         (seq (array/sort! filled))]))))

(mf/defc tokens-section*
  {::mf/private true}
  [{:keys [tokens-lib]}]
  (let [objects         (mf/deref refs/workspace-page-objects)
        selected        (mf/deref refs/selected-shapes)
        open-status     (mf/deref ref:token-type-open-status)

        selected-shapes
        (mf/with-memo [selected objects]
          (into [] (keep (d/getf objects)) selected))

        active-theme-tokens
        (mf/with-memo [tokens-lib]
          (if tokens-lib
            (ctob/get-tokens-in-active-sets tokens-lib)
            {}))

        ;; Resolve tokens as second step
        active-theme-tokens'
        (sd/use-resolved-tokens* active-theme-tokens)

        ;; This only checks for the currently explicitly selected set
        ;; name, it is ephimeral and can be nil
        selected-token-set-name
        (mf/deref refs/selected-token-set-name)

        selected-token-set
        (when selected-token-set-name
          (some-> tokens-lib (ctob/get-set selected-token-set-name)))

        ;; If we have not selected any set explicitly we just
        ;; select the first one from the list of sets
        selected-token-set-tokens
        (when selected-token-set
          (ctob/get-tokens-map selected-token-set))

        tokens
        (mf/with-memo [active-theme-tokens selected-token-set-tokens]
          (merge active-theme-tokens selected-token-set-tokens))

        tokens
        (sd/use-resolved-tokens* tokens)

        tokens-by-type
        (mf/with-memo [tokens selected-token-set-tokens]
          (let [tokens (reduce-kv (fn [tokens k _]
                                    (if (contains? selected-token-set-tokens k)
                                      tokens
                                      (dissoc tokens k)))
                                  tokens
                                  tokens)]
            (ctob/group-by-type tokens)))

        active-token-sets-names
        (mf/with-memo [tokens-lib]
          (some-> tokens-lib (ctob/get-active-themes-set-names)))

        token-set-active?
        (mf/use-fn
         (mf/deps active-token-sets-names)
         (fn [name]
           (contains? active-token-sets-names name)))

        [empty-group filled-group]
        (mf/with-memo [tokens-by-type]
          (get-sorted-token-groups tokens-by-type))]

    (mf/with-effect [tokens-lib selected-token-set-name]
      (when (and tokens-lib
                 (or (nil? selected-token-set-name)
                     (and selected-token-set-name
                          (not (ctob/get-set tokens-lib selected-token-set-name)))))
        (let [match (->> (ctob/get-sets tokens-lib)
                         (first))]
          (when match
            (st/emit! (dwtl/set-selected-token-set-name (ctob/get-name match)))))))

    [:*
     [:& token-context-menu]
     [:div {:class (stl/css :sets-header-container)}
      [:> text* {:as "span" :typography "headline-small" :class (stl/css :sets-header)} (tr "workspace.tokens.tokens-section-title" selected-token-set-name)]
      [:div {:class (stl/css :sets-header-status) :title (tr "workspace.tokens.inactive-set-description")}
       ;; NOTE: when no set in tokens-lib, the selected-token-set-name
       ;; will be `nil`, so for properly hide the inactive message we
       ;; check that at least `selected-token-set-name` has a value
       (when (and (some? selected-token-set-name)
                  (not (token-set-active? selected-token-set-name)))
         [:*
          [:> i/icon* {:class (stl/css :sets-header-status-icon) :icon-id i/eye-off}]
          [:> text* {:as "span" :typography "body-small" :class (stl/css :sets-header-status-text)}
           (tr "workspace.tokens.inactive-set")]])]]

     (for [type filled-group]
       (let [tokens (get tokens-by-type type)]
         [:> token-group* {:key (name type)
                           :is-open (get open-status type false)
                           :type type
                           :selected-shapes selected-shapes
                           :active-theme-tokens active-theme-tokens'
                           :tokens tokens}]))

     (for [type empty-group]
       [:> token-group* {:key (name type)
                         :type type
                         :selected-shapes selected-shapes
                         :active-theme-tokens active-theme-tokens'
                         :tokens []}])]))
