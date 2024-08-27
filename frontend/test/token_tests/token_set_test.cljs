(ns token-tests.token-set-test
  (:require
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [cljs.test :as t]))

(t/deftest toggle-active-theme-id-test
  (t/testing "toggles active theme id"
    (let [state {:workspace-data {:token-themes-index {1 {:id 1}}}}]
      (t/testing "activates theme with id")
      (t/is (= (wtts/toggle-active-theme-id 1 state) #{1})))

    (let [state {:workspace-data {:token-active-themes #{1}
                                  :token-themes-index {1 {:id 1}}}}]
      (t/testing "missing temp theme returns empty set"
        (t/is (= #{} (wtts/toggle-active-theme-id 1 state)))))

    (let [state {:workspace-data {:token-theme-temporary-id :temp
                                  :token-active-themes #{1}
                                  :token-themes-index {1 {:id 1}}}}]
      (t/testing "empty set returns temp theme"
        (t/is (= #{:temp} (wtts/toggle-active-theme-id 1 state)))))

    (let [state {:workspace-data {:token-active-themes #{2 3 4}
                                  :token-themes-index {1 {:id 1}
                                                       2 {:id 2}
                                                       3 {:id 3}
                                                       4 {:id 4 :group :different}}}}]
      (t/testing "removes same group themes and keeps different group themes"
        (t/is (= #{1 4} (wtts/toggle-active-theme-id 1 state)))))

    (let [state {:workspace-data {:token-active-themes #{1 2 3 4}}
                 :token-themes-index {1 {:id 1}
                                      2 {:id 2}
                                      3 {:id 3}
                                      4 {:id 4 :group :different}}}]
      (t/testing "removes theme when active"
        (t/is (= #{4 3 2} (wtts/toggle-active-theme-id 1 state)))))))
