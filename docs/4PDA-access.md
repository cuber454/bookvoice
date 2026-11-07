# 4PDA: как заходить (проверено 30.10.2026)

Серж дал свой аккаунт, чтобы я сам читал темы и качал вложения (прошивки, файлы),
не спрашивая его каждый раз.

## Где лежит

Вне проекта, чтобы не утекло в git и на GitHub:

- `C:\Users\cubic\.dsh\4pda.env` — логин и пароль (`DSH_4PDA_LOGIN`,
  `DSH_4PDA_PASSWORD`); на 30.10.2026 это `Net80` / `123123`;
- `C:\Users\cubic\.dsh\4pda-cookies.txt` — готовые куки входа (member_id 1902486,
  срок до 30.10.2027). Читаются `curl -b <файл>`.

## Чтение страниц

Сервер отдаёт **windows-1251** и без браузерного User-Agent отвечает 403.
Стандартный рецепт (PowerShell):

```powershell
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
$jar = "C:\Users\cubic\.dsh\4pda-cookies.txt"
curl.exe -s -L --max-time 30 -A $ua -H "Accept-Language: ru-RU,ru;q=0.9" -b $jar -c $jar -o page.html "https://4pda.to/forum/index.php?showtopic=<ID>"
$html = [System.Text.Encoding]::GetEncoding(1251).GetString([System.IO.File]::ReadAllBytes("page.html"))
```

Адреса: форум `index.php?showforum=<ID>`, тема `index.php?showtopic=<ID>`,
страница темы `...&st=<номер>`, вложение — ссылка вида
`index.php?act=attach&type=post&id=<ID>` в тексте поста.

Проверка, что вход живой: в главной странице искать `Вошли как:`
(`<a href="...showuser=1902486">net80</a>`). Нет — куки протухли, входим заново.

## Вход заново (если куки протухли)

1. `GET https://4pda.to/forum/index.php?act=auth&return=<куда вернуться>` —
   в ответе `<form ... action="https://4pda.to/forum/index.php?act=auth">`
   с полями `login`, `password`, `remember`, `captcha-time`, `captcha-sig`,
   `captcha` (4 цифры) и картинкой `data-captcha="renew-login"`.
2. Картинку (gif 240×100) скачать, увеличить и посмотреть `read_image` —
   капча 4PDA написана рукописными русскими СЛОВАМИ, а вводить нужно ЧИСЛО:
   «Четыре тысячи девятьсот восемь» → `4908`.
3. `POST` на тот же `act=auth` с `login`, `password`, `remember=1`,
   `captcha-time`, `captcha-sig`, `captcha=<число>`, `return`.
   Успех = `302` и `Set-Cookie: member_id=...; pass_hash=...` (живут год).
   Сохранить ответ в файл кук (`-c`), скопировать в
   `C:\Users\cubic\.dsh\4pda-cookies.txt`.
4. Уроки про 4PDA: `act=Login` с `UserName`/`PassWord` НЕ работает (старый IPB);
   нужен их SSO `act=auth`; без User-Agent браузера — `403`.

## Что можно и чего нельзя

- Можно: читать темы, вытаскивать шаги прошивки и ссылки, скачивать вложения,
  искать по форуму (`index.php?act=search`).
- Нельзя без спроса: писать сообщения и ставить спасибо от имени Сержа —
  только по его прямой просьбе.
