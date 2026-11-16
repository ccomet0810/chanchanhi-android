package dev.ccomet.chanchanhi04.agent
import com.example.aplassistmvp0.accessibility.NodeBounds
import org.junit.Assert.*
import org.junit.Test

class InteractionBindingTest {
    private val bounds=NodeBounds(0,100,100,160)
    private val shifted=NodeBounds(0,80,100,140)
    private val button=Element(4,"0/2",null,"Button","다음\n단계",bounds,true,true,true,false,false,null,null,null,null)
    private val screen=Observation(1,"public.fixture",1,1,1,100,200,true,listOf(button))
    private fun resolve(s:Observation=screen,label:String="다음 단계",role:String="Button")=InteractionBinding.resolve(s,"public.fixture",1,null,role,shifted,label)
    @Test fun relayoutAfterClickBindsOnlyToAUniquePublicIdentity(){assertEquals(button,resolve());assertNull(resolve(screen.copy(elements=listOf(button,button.copy(id=5,path="0/3")))));assertNull(resolve(label="다른 행동"));assertNull(resolve(role="TextView"))}
    @Test fun privateInputsOtherWindowsAndDisabledNodesCannotUseTheLabelFallback(){assertNull(resolve(screen.copy(elements=listOf(button.copy(editable=true)))));assertNull(resolve(screen.copy(elements=listOf(button.copy(enabled=false)))));assertNull(InteractionBinding.resolve(screen,"another.fixture",1,null,"Button",shifted,"다음 단계"));assertNull(InteractionBinding.resolve(screen,"public.fixture",2,null,"Button",shifted,"다음 단계"))}
    @Test fun uniqueResourcesMayMoveButDuplicateResourcesDoNotBecomeEvidence(){val resource=button.copy(resource="public:id/next");val s=screen.copy(elements=listOf(resource));assertEquals(resource,InteractionBinding.resolve(s,"public.fixture",1,"public:id/next","Button",shifted,null));assertNull(InteractionBinding.resolve(s.copy(elements=listOf(resource,resource.copy(id=5))),"public.fixture",1,"public:id/next","Button",shifted,null))}
}
