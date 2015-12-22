(ns uxbox.ui.dashboard.builtins)

(def ^:static +color-collections+
  [{:name "Generic 1"
    :id 1
    :colors #{:00f9ff
              :009fff
              :0078ff
              :005eff
              :0900ff
              :7502f1
              :ffe705
              :00ffab
              :f52105
              }}
   {:name "Generic 2"
    :id 2
    :colors #{:20f9ff
              :209fff
              :2078ff
              :205eff
              :2900ff
              :3502f1
              :3fe705
              :30ffab
              :352105
              }}])

(def ^:static +color-collections-by-id+
  (let [data (transient {})]
    (run! #(assoc! data (:id %) %) +color-collections+)
    (persistent! data)))
