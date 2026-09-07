package ai.moeru.airicraft.systemone.voxel;

import org.junit.jupiter.api.Test;
import java.util.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemPickupTest {
	static ItemObservation.Drop drop(double x, long tick) {
		return new ItemObservation.Drop("entity", "log", 1, new ItemObservation.Point(x,1,2.5),tick);
	}
	static StoneAcquisition.World world(Map<String,Integer> inventory, List<ItemObservation.Drop> drops) {
		var known = new HashMap<Pos,Seen>();
		for (int x=-1;x<=4;x++) for (int z=0;z<=4;z++) for (int y=0;y<=2;y++)
			known.put(new Pos(x,y,z),new Seen(y==0?"stone":"air",y!=0,true,y==0,15,0));
		return new StoneAcquisition.World(new Pose(.5,2.62,.5,0,0),new Pos(0,1,0),inventory,known,Set.of(),SurvivalPolicy.Vitals.healthy(),drops);
	}
	private static ItemPickup.Decision advance(ItemPickup.State state, StoneAcquisition.World world, long tick, Outcome feedback) {
		return ItemPickup.advance(state,world,Optional.ofNullable(feedback),tick,Set.of("stone"),p->true);
	}
	@Test void arrivalAndDisappearanceDoNotSubstituteForInventoryEvidence() {
		var item=drop(.5,0);var world=world(Map.of(),List.of(item));
		var first=assertInstanceOf(ItemPickup.Action.class,advance(ItemPickup.State.begin(item,1,0),world,1,null));
		var arrived=assertInstanceOf(ItemPickup.Wait.class,advance(first.state(),world(Map.of(),List.of()),2,Outcome.success("arrived")));
		assertEquals(12,arrived.until());
		var another=assertInstanceOf(ItemPickup.Action.class,advance(arrived.state(),world(Map.of(),List.of()),12,null));
		assertNotEquals(first.command(),another.command());
		var done=assertInstanceOf(ItemPickup.Done.class,advance(another.state(),world(Map.of("log",1),List.of()),13,null));
		assertEquals(ResultKind.SUCCEEDED,done.outcome().kind());
	}
	@Test void newObservedPositionCanReuseAnEarlierRejectedStance() {
		var old=drop(.5,0);var moved=drop(2.5,10);var destination=new Pos(2,1,2);
		var state=new ItemPickup.State(old,1,Set.of(destination),new ItemPickup.Seeking(),2,600);
		var next=assertInstanceOf(ItemPickup.Action.class,advance(state,world(Map.of(),List.of(moved)),10,null));
		assertEquals(destination,assertInstanceOf(VoxelCommand.Navigate.class,next.command()).stance());
		assertEquals(moved,next.state().target());
	}
	@Test void lastSeenPositionExpiresAndWorkIsBounded() {
		var item=drop(.5,0);
		var expired=assertInstanceOf(ItemPickup.Done.class,advance(ItemPickup.State.begin(item,1,0),world(Map.of(),List.of()),201,null));
		assertEquals(Outcome.failure("item_pickup_observation_expired"),expired.outcome());
		var exhausted=new ItemPickup.State(item,1,Set.of(),new ItemPickup.Seeking(),16,600);
		assertEquals(Outcome.failure("item_pickup_budget_exhausted"),assertInstanceOf(ItemPickup.Done.class,advance(exhausted,world(Map.of(),List.of(item)),1,null)).outcome());
	}
	@Test void failedNavigationSelectsAnotherObservedStance() {
		var item=drop(.5,0);var world=world(Map.of(),List.of(item));
		var first=assertInstanceOf(ItemPickup.Action.class,advance(ItemPickup.State.begin(item,1,0),world,1,null));
		var next=assertInstanceOf(ItemPickup.Action.class,advance(first.state(),world,2,Outcome.failure("blocked")));
		assertNotEquals(first.command(),next.command());
		assertEquals(2,next.state().work());
	}
	@Test void acquisitionCollectsVisibleDropsWithoutBootstrappingAHarvestTool() {
		var book=new ProductionKnowledge("pickup",List.of(),List.of(new ProductionKnowledge.Harvest("log",List.of("log"),List.of("axe"),ProductionKnowledge.Technique.EXPOSED)));
		var domain=new ProductionDomain(book);
		var child=assertInstanceOf(Child.class,domain.decide(new View<>(1,ProductionDomain.Acquire.root("log",1),false,1,Optional.empty(),Optional.empty()),world(Map.of(),List.of(drop(.5,0)))));
		assertInstanceOf(ProductionDomain.Pickup.class,child.child());
		assertEquals("pickup:entity",assertInstanceOf(ProductionDomain.Acquire.class,child.continuation()).method());
	}
	@Test void gatherUsesActualItemsDespiteVisitedStancesAndDoesNotRepeatAFailedPickup() {
		var rule=new ProductionKnowledge.Harvest("log",List.of("log"),List.of(),ProductionKnowledge.Technique.EXPOSED);
		var domain=new ProductionDomain(new ProductionKnowledge("pickup",List.of(),List.of(rule)));
		var world=world(Map.of(),List.of(drop(.5,0)));
		var gather=new ProductionDomain.Gather(rule,1,world.feet(),0,Set.of(),Set.copyOf(world.known().keySet()),null);
		var child=assertInstanceOf(Child.class,domain.decide(new View<>(1,gather,false,1,Optional.empty(),Optional.empty()),world));
		assertInstanceOf(ProductionDomain.Pickup.class,child.child());
		var after=assertInstanceOf(ProductionDomain.AfterPickup.class,child.continuation());
		var next=domain.decide(new View<>(1,after,false,2,Optional.empty(),Optional.of(Outcome.failure("unreachable"))),world);
		assertFalse(next instanceof Child<?,?> repair && repair.child() instanceof ProductionDomain.Pickup);
	}
}
