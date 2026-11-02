// Cloudflare Worker: Ko-fi's webhook → GitHub, so a new supporter's name reaches the site's
// thank-you (.github/workflows/supporters.yml opens a pull request with it).
// Settings (Worker › Settings › Variables and Secrets, as secrets):
//   KOFI_TOKEN    the "Verification Token" of Ko-fi › Settings › API › Webhooks
//   GITHUB_TOKEN  a fine-grained token for xlollx/Trackhop only, Contents: Read and write
// Only the name of a public supporter leaves here: never the amount, the email or the message.
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("Trackhop supporters webhook\n");
    let data;
    try {
      const form = await request.formData();
      data = JSON.parse(form.get("data"));
    } catch {
      return new Response("Not a Ko-fi webhook\n", { status: 400 });
    }
    if (!env.KOFI_TOKEN || data.verification_token !== env.KOFI_TOKEN) {
      return new Response("Wrong verification token\n", { status: 403 });
    }
    // A supporter who chose to stay private is never named.
    if (!data.is_public) return new Response("ok\n");
    const name = String(data.from_name || "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 40);
    if (!name || /^(someone|anonymous)$/i.test(name)) return new Response("ok\n");
    const r = await fetch("https://api.github.com/repos/xlollx/Trackhop/dispatches", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "trackhop-supporters",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ event_type: "supporter", client_payload: { name, source: "ko-fi" } }),
    });
    return new Response(r.ok ? "ok\n" : `GitHub answered ${r.status}\n`, { status: r.ok ? 200 : 502 });
  },
};
