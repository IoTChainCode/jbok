package jbok.app

import java.net.URI

import com.thoughtworks.binding.Binding.{BindingSeq, Var, Vars}
import com.thoughtworks.binding.{Binding, dom}
import jbok.app.components.{SelectItem, SelectMenu, Spinner}
import jbok.app.views.Nav.{Tab, TabList}
import jbok.app.views._
import jbok.network.execution._
import org.scalajs.dom._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@JSImport("css/normalize.css", JSImport.Namespace)
@js.native
object NormalizeCss extends js.Object

@JSImport("css/app.css", JSImport.Namespace)
@js.native
object AppCss extends js.Object

object JbokApp {
  val normalizeCss = NormalizeCss
  val appCss = AppCss

  val uri    = new URI(s"ws://localhost:8888")

  val selectMenu =
    new SelectMenu("please select max").render(Vars(SelectItem("50", "50"), SelectItem("100", "100")))

  val config = AppConfig.default
  val state = AppState(Var(config))

  JbokClient(config.uri).unsafeToFuture().map(c => state.client.value = Some(c))

  val statusView       = StatusView(state).render
  val accountsView     = AccountsView(state).render()
  val blocksView       = BlocksView(state).render()
  val transactionsView = TxsView.render()
  val simulationsView  = SimulationsView.render()
  val configView       = ConfigView.render()

  val tabs = Vars(
    Tab("Accounts", accountsView, "fa-user-circle"),
    Tab("Blocks", blocksView, "fa-th-large"),
    Tab("Transactions", transactionsView, "fa-arrow-circle-right"),
    Tab("Simulations", simulationsView, "fa-stethoscope"),
    Tab("", configView, "fa-cogs")
  )

  val tabList   = TabList(tabs, Var(tabs.value.head))
  val searchBar = SearchBar(state).render

  @dom val left: Binding[Node] =
    <div class="nav-left">
    {
      for {
        tab <- tabList.tabs
      } yield {
        val isSelected = tabList.selected.bind == tab
        <div class={s"tab ${if (isSelected) "selected" else ""}"}
             onclick={_: Event => tabList.selected.value = tab}>
          <i class={s"fas fa-fw fa-lg ${tab.icon}"}></i>
          { tab.name }
        </div>
      }
    }
    </div>

  @dom val right: Binding[Node] =
    <div class="nav-right">
      <div class="tab searchbar">{searchBar.bind}</div>
    </div>

  val navBar = Nav.render(left, right)

  @dom def render: Binding[BindingSeq[Node]] =
    <header>
      {navBar.bind}
      {statusView.bind}
    </header>
    <main>
    {
      for {
        tab <- tabList.tabs
      } yield {
        val isSelected = tabList.selected.bind == tab
        <div class={s"tab-content ${if (isSelected) "selected" else ""}"}>
          {tab.content.bind}
        </div>
      }
    }
    </main>
    <footer>
      {Copyright.render.bind}
    </footer>

  def main(args: Array[String]): Unit =
    dom.render(document.body, render)
}