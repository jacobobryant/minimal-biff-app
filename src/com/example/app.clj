(ns com.example.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.example.middleware :as mid]
            [com.example.ui :as ui]
            [com.example.settings :as settings]
            [rum.core :as rum]
            [xtdb.api :as xt]
            [ring.adapter.jetty9 :as jetty]
            [cheshire.core :as cheshire]))

(defn set-foo [{:keys [session params com.example/data] :as ctx}]
  (swap! data assoc-in [:users (:uid session) :user/foo] (:foo params))
  {:status 303
   :headers {"location" "/"}})

(defn bar-form [{:keys [value]}]
  (biff/form
   {:hx-post "/set-bar"
    :hx-swap "outerHTML"}
   [:label.block {:for "bar"} "Bar: "
    [:span.font-mono (pr-str value)]]
   [:.h-1]
   [:.flex
    [:input.w-full#bar {:type "text" :name "bar" :value value}]
    [:.w-3]
    [:button.btn {:type "submit"} "Update"]]
   [:.h-1]
   [:.text-sm.text-gray-600
    "This demonstrates updating a value with HTMX."]))

(defn set-bar [{:keys [session params com.example/data] :as ctx}]
  (swap! data assoc-in [:user (:uid session) :user/bar] (:bar params))
  (biff/render (bar-form {:value (:bar params)})))

(defn ui-message [{:msg/keys [text sent-at]}]
  [:.mt-3 {:_ "init send newMessage to #message-header"}
   [:.text-gray-600 (biff/format-date sent-at "dd MMM yyyy HH:mm:ss")]
   [:div text]])

(defn send-message [{:keys [session com.example/data] :as ctx} {:keys [text]}]
  (let [{:keys [text]} (cheshire/parse-string text true)
        message {:msg/id (random-uuid)
                 :msg/text text
                 :msg/user (:uid session)
                 :msg/sent-at (java.util.Date.)}
        html (rum/render-static-markup
              [:div#messages {:hx-swap-oob "afterbegin"}
               (ui-message message)])]
    (swap! data assoc-in [:messages (:msg/id message)] message)
    (doseq [ws (doto (:chat-clients @data) prn)]
     (jetty/send! ws html))))

(defn chat [{:keys [biff/db com.example/data]}]
  (let [messages (filterv (fn [{:msg/keys [sent-at]}]
                            (<= (inst-ms (biff/add-seconds (java.util.Date.) (* -60 10)))
                                (inst-ms sent-at)))
                          (vals (:messages @data)))]
    [:div {:hx-ext "ws" :ws-connect "/chat"}
     [:form.mb-0 {:ws-send true
                  :_ "on submit set value of #message to ''"}
      [:label.block {:for "message"} "Write a message"]
      [:.h-1]
      [:textarea.w-full#message {:name "text"}]
      [:.h-1]
      [:.text-sm.text-gray-600
       "Sign in with an incognito window to have a conversation with yourself."]
      [:.h-2]
      [:div [:button.btn {:type "submit"} "Send message"]]]
     [:.h-6]
     [:div#message-header
      {:_ "on newMessage put 'Messages sent in the past 10 minutes:' into me"}
      (if (empty? messages)
        "No messages yet."
        "Messages sent in the past 10 minutes:")]
     [:div#messages
      (map message (sort-by :msg/sent-at #(compare %2 %1) messages))]]))

(defn app [{:keys [session biff/db com.example/data] :as ctx}]
  (let [{:user/keys [foo bar]} (get-in @data [:users (:uid session)])]
    (ui/page
     {}
     (biff/form
      {:action "/set-foo"}
      [:label.block {:for "foo"} "Foo: "
       [:span.font-mono (pr-str foo)]]
      [:.h-1]
      [:.flex
       [:input.w-full#foo {:type "text" :name "foo" :value foo}]
       [:.w-3]
       [:button.btn {:type "submit"} "Update"]]
      [:.h-1]
      [:.text-sm.text-gray-600
       "This demonstrates updating a value with a plain old form."])
     [:.h-6]
     (bar-form {:value bar})
     [:.h-6]
     (chat ctx))))

(defn ws-handler [{:keys [com.example/data] :as ctx}]
  {:status 101
   :headers {"upgrade" "websocket"
             "connection" "upgrade"}
   :ws {:on-connect (fn [ws]
                      (swap! data update :chat-clients conj ws))
        :on-text (fn [ws text-message]
                   (send-message ctx {:ws ws :text text-message}))
        :on-close (fn [ws status-code reason]
                    (swap! data update :chat-clients disj ws))}})

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(defn echo [{:keys [params]}]
  {:status 200
   :headers {"content-type" "application/json"}
   :body params})

(def module
  {:static {"/about/" about-page}
   :routes ["" {:middleware [mid/wrap-add-session-id]}
            ["/" {:get app}]
            ["/set-foo" {:post set-foo}]
            ["/set-bar" {:post set-bar}]
            ["/chat" {:get ws-handler}]]
   :api-routes [["/api/echo" {:post echo}]]})
