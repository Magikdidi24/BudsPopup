"""Turn the Sketchfab Buds4 Pro model into the animated asset used by the popup.

    blender -b --factory-startup --python tools/build_model.py -- model/buds4pro_source.glb \
        app/src/main/assets/buds.glb [--preview DIR HDR]

Source: "SUMSUNG Buds 4 Pro" by zhe_kan (sketchfab.com/zhe_kan), CC BY-NC-SA 4.0.
Changes: logo removed, earbuds split into BudL / BudR, lid pivot moved to the hinge,
materials renamed, case and lid animation baked (open sequence, then an idle loop).
The buds are not animated here: the app moves each one when it leaves or returns to the case.

Blender is Z-up in cm; glTF is Y-up, so a Blender point (x, y, z) is (x, z, -y) in the app.
The front of the case faces -Y.
"""
import math
import sys

import bpy
from mathutils import Euler, Vector

FPS = 30
OPEN_END = 66     # frame where the open sequence stops (2.2 s)
IDLE_START = 70   # idle loop runs from here...
IDLE_END = 250    # ...to here (6 s), then the app wraps back to IDLE_START
BUD_X = 4.3       # where a bud out of the case floats, for the preview (BudsScene.kt moves the buds)
BUD_Z = -0.2
LID_OPEN = -100.0  # degrees around X; negative lifts the front edge

# Camera shared with the app (BudsScene.kt): eye and target in Blender coords, focal in mm
# on a 24 mm tall sensor, which is what Filament's setLensProjection uses.
CAM_EYE = (0.0, -21.0, 7.5)
CAM_TARGET = (0.0, 0.0, 1.3)
CAM_FOCAL = 50.0
ASPECT = (640, 400)

MATERIALS = {
    'Case': 'Shell', 'Case.001': 'Trim', 'Case.002': 'Blade', 'USB type c.001': 'Dark',
    'Case_transparent_plastic': 'Lid', 'Light': 'Led', 'USB type c': 'PortMetal',
    'USB type c.002': 'PortGold',
}


def ease(t):  # cubic ease-in-out on [0, 1]
    t = min(max(t, 0.0), 1.0)
    return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def ease_out_back(t, s=1.4):
    t = min(max(t, 0.0), 1.0) - 1
    return 1 + (s + 1) * t ** 3 + s * t ** 2


def phase(f, a, b):  # 0..1 progress of frame f inside [a, b]
    return min(max((f - a) / (b - a), 0.0), 1.0)


def lerp(a, b, t):
    return a + (b - a) * t


def prepare(src):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=src)
    objs = bpy.data.objects
    bpy.data.objects.remove(objs['Curve.001'])  # brand logo
    meshes = [o for o in objs if o.type == 'MESH']
    for o in objs:
        o.select_set(o in meshes)
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.parent_clear(type='CLEAR_KEEP_TRANSFORM')
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    for o in [o for o in objs if o.type == 'EMPTY']:
        bpy.data.objects.remove(o)
    for old, new in MATERIALS.items():
        bpy.data.materials[old].name = new

    # Earbuds: one mesh of mirrored loose parts, split by side (x < 0 is the left bud).
    buds = objs['Cube.004']
    for o in objs:
        o.select_set(o is buds)
    bpy.context.view_layer.objects.active = buds
    bpy.ops.mesh.separate(type='LOOSE')
    parts = list(bpy.context.selected_objects)
    groups = {side: [p for p in parts if side * sum(v.co.x for v in p.data.vertices) > 0]
              for side in (-1, 1)}  # grouped up front: join() deletes the parts it merges
    for side, name in ((-1, 'BudL'), (1, 'BudR')):
        mine = groups[side]
        for o in objs:
            o.select_set(o in mine)
        bpy.context.view_layer.objects.active = mine[0]
        bpy.ops.object.join()
        bud = bpy.context.view_layer.objects.active
        bud.name = bud.data.name = name
        bpy.ops.object.origin_set(type='ORIGIN_GEOMETRY', center='BOUNDS')

    # Lid pivots on the back bottom edge of the lid.
    lid = objs['Case top.001']
    lid.name = 'Lid'
    lo = Vector([min(v.co[i] for v in lid.data.vertices) for i in range(3)])
    hi = Vector([max(v.co[i] for v in lid.data.vertices) for i in range(3)])
    bpy.context.scene.cursor.location = (0.0, hi.y, lo.z)
    for o in objs:
        o.select_set(o is lid)
    bpy.context.view_layer.objects.active = lid
    bpy.ops.object.origin_set(type='ORIGIN_CURSOR')

    root = bpy.data.objects.new('CaseRoot', None)
    bpy.context.scene.collection.objects.link(root)
    for name in ('Case', 'Case.002', 'Cube.001', 'USB type C', 'Lid'):
        objs[name].parent = root

    # The app renders the lid with plain alpha blending, no screen-space refraction.
    m = bpy.data.materials['Lid']
    bsdf = m.node_tree.nodes['Principled BSDF']
    bsdf.inputs['Transmission Weight'].default_value = 0.0
    bsdf.inputs['Base Color'].default_value = (1.0, 1.0, 1.0, 1.0)
    bsdf.inputs['Alpha'].default_value = 0.22
    bsdf.inputs['Roughness'].default_value = 0.05
    m.surface_render_method = 'BLENDED'
    return root, lid, objs['BudL'], objs['BudR']


def animate(root, lid):
    """Case pop-in, lid opening, then the idle sway. The buds have no keys: the app moves each
    one out of or back into the case when that bud leaves or returns (BudsScene.kt)."""
    for o in (root, lid):
        o.rotation_mode = 'XYZ'
    for f in range(0, IDLE_END + 1):
        if f <= OPEN_END:
            s = lerp(0.82, 1.0, ease_out_back(phase(f, 0, 14)))
            root.scale = (s, s, s)
            root.rotation_euler = (0, 0, math.radians(lerp(-18, 0, ease(phase(f, 0, 40)))))
            # The lid waits for the card to finish sliding in, so the opening is seen.
            lid.rotation_euler = (math.radians(LID_OPEN * ease_out_back(phase(f, 20, 44), 1.1)), 0, 0)
        else:
            t = (max(f, IDLE_START) - IDLE_START) / (IDLE_END - IDLE_START)
            root.scale = (1, 1, 1)
            root.rotation_euler = (0, 0, math.radians(12 * math.sin(2 * math.pi * t)))
            lid.rotation_euler = (math.radians(LID_OPEN), 0, 0)
        for o in (root, lid):
            o.keyframe_insert('location', frame=f)
            o.keyframe_insert('rotation_euler', frame=f)
            o.keyframe_insert('scale', frame=f)
    sc = bpy.context.scene
    sc.render.fps = FPS
    sc.frame_start, sc.frame_end = 0, IDLE_END


def export(dst):
    bpy.ops.export_scene.gltf(
        filepath=dst, export_format='GLB', export_animations=True,
        export_animation_mode='SCENE', export_force_sampling=True, export_cameras=False,
        export_lights=False, export_extras=False, export_yup=True)


def preview(out, hdr):
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_EEVEE'
    sc.render.film_transparent = True
    sc.render.resolution_x, sc.render.resolution_y = ASPECT
    w = bpy.data.worlds.new('w')
    sc.world = w
    w.use_nodes = True
    env = w.node_tree.nodes.new('ShaderNodeTexEnvironment')
    env.image = bpy.data.images.load(hdr)
    w.node_tree.links.new(env.outputs[0], w.node_tree.nodes['Background'].inputs[0])
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
    sc.collection.objects.link(cam)
    sc.camera = cam
    cam.data.sensor_fit = 'VERTICAL'
    cam.data.sensor_height = 24.0
    cam.data.lens = CAM_FOCAL
    cam.location = CAM_EYE
    cam.rotation_euler = (Vector(CAM_TARGET) - Vector(CAM_EYE)).to_track_quat('-Z', 'Y').to_euler()
    from bpy_extras.object_utils import world_to_camera_view
    for f in (0, 12, 20, 30, 66, 160):
        sc.frame_set(f)
        sc.render.filepath = f'{out}/f{f:03d}.png'
        bpy.ops.render.render(write_still=True)
    for name, side in (('BudL', -1), ('BudR', 1)):  # where each bud lands, for the battery row under it
        p = world_to_camera_view(sc, cam, Vector((side * BUD_X, 0, BUD_Z)))
        print(f'SCREEN {name} x={p.x:.3f} y={1 - p.y:.3f}')
    p = world_to_camera_view(sc, cam, Vector((0, 0, -1.415)))
    print(f'SCREEN case-bottom y={1 - p.y:.3f}')


def main():
    args = sys.argv[sys.argv.index('--') + 1:]
    root, lid, _, _ = prepare(args[0])
    animate(root, lid)
    export(args[1])
    if '--preview' in args:
        i = args.index('--preview')
        preview(args[i + 1], args[i + 2])


main()
