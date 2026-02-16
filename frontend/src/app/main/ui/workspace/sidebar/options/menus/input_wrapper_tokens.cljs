(ns app.main.ui.workspace.sidebar.options.menus.input-wrapper-tokens
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.token :as tk]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc numeric-input-wrapper*
  [{:keys [value attr applied-token align on-detach placeholder input-type class] :rest props}]
  (let [tokens (mf/use-ctx muc/active-tokens-by-type)

        tokens (mf/with-memo [tokens input-type]
                 (delay
                   (-> (deref tokens)
                       (select-keys (get tk/tokens-by-input (or input-type attr)))
                       (not-empty))))

        on-detach-attr
        (mf/use-fn
         (mf/deps on-detach attr)
         #(on-detach % attr))

        props  (mf/spread-props props
                                {:placeholder (or placeholder
                                                  (if (= :multiple value)
                                                    (tr "settings.multiple")
                                                    "--"))
                                 :class [class (stl/css :numeric-input-wrapper)]
                                 :applied-token applied-token
                                 :tokens (if (delay? tokens) @tokens tokens)
                                 :align align
                                 :on-detach on-detach-attr
                                 :name attr
                                 :value value})]
    [:> numeric-input* props]))