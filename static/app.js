const API = "/api/v1";
const errEl = document.getElementById("err");

async function getJSON(url) {
  const res = await fetch(url, { headers: { "Accept-Language": "ru" } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${url}`);
  return res.json();
}

async function loadCategories() {
  const ul = document.getElementById("categories");
  try {
    const cats = await getJSON(`${API}/categories`);
    if (!Array.isArray(cats) || cats.length === 0) {
      ul.innerHTML = '<li class="muted">категорий нет</li>';
      return;
    }
    ul.innerHTML = cats
      .map((c) => `<li>${c.name ?? c.slug ?? c.id}${(c.breeds?.length ?? 0) ? ` — ${c.breeds.length} пород` : ""}</li>`)
      .join("");
  } catch (e) {
    ul.innerHTML = "";
    errEl.textContent = `Категории: ${e.message}`;
  }
}

function listingCard(l) {
  const title = l.title ?? "(без названия)";
  const price = l.price != null ? `${l.price} ${l.currency ?? ""}` : "—";
  const city = l.locationCity ?? "";
  return `<div class="card"><div>${title}</div><div class="muted">${city} · ${l.status ?? ""}</div><div class="price">${price}</div></div>`;
}

async function loadListings() {
  const el = document.getElementById("listings");
  try {
    const page = await getJSON(`${API}/listings?page=0&size=10`);
    const items = Array.isArray(page) ? page : page.content ?? [];
    if (items.length === 0) {
      el.innerHTML = '<p class="muted">объявлений нет</p>';
      return;
    }
    el.innerHTML = items.map(listingCard).join("");
  } catch (e) {
    el.innerHTML = "";
    errEl.textContent = `Объявления: ${e.message}`;
  }
}

loadCategories();
loadListings();
